package io.github.jiangzhengxun.taskmanager.support.testdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.FinalResult;
import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.Kind;
import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.Point;
import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.Session;
import io.github.jiangzhengxun.taskmanager.support.testdb.NativeLifecycleObservation.Status;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Initializer-rejection controls under native observation. Each control is enabled only when
 * {@code -Dtaskmanager.guardedNativeRejection.control=<id>} names it, so a default suite reports every control as
 * skipped (never passed). Exactly one control is meant to run per fresh JVM, with the reviewed startup agent.
 *
 * <p>Scope: only {@link GuardedTestDatabaseInitializer#initialize} rejecting an unrefreshed
 * {@link GenericApplicationContext}. Not covered: Spring refresh, annotation-driven bootstrapping, bean-definition
 * rejection, Flyway/JPA ordering and real database access. Constructor observation covers normal completion only;
 * a Hikari constructor that throws is not observed as completion.
 *
 * <p>Targets are reached only by name, loaded without initialization after the transformer is installed. No pool is
 * created and neither {@code getConnection} nor {@code connect} is called. Observation is finished only after the
 * initializer call and context close have returned; no native call overlaps finish.
 */
class GuardedTestDatabaseNativeRejectionTest {

    private static final String CONTROL = "taskmanager.guardedNativeRejection.control";
    private static final String HIKARI = "com.zaxxer.hikari.HikariDataSource";
    private static final String PG_DRIVER = "org.postgresql.Driver";
    private static final String MODE = TestDatabaseGuardInitializer.MODE_PROPERTY;
    private static final Set<Point> ALL_POINTS = EnumSet.allOf(Point.class);

    /** An explicitly enabled run must name a known control and must have exactly one startup agent. */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = ".*")
    void enabledRunHasKnownControlAndStartupAgent() {
        assertThat(System.getProperty(CONTROL)).matches("R1|R2|R3");
        assertThat(ManagementFactory.getRuntimeMXBean().getInputArguments())
                .filteredOn(argument -> argument.startsWith("-javaagent:"))
                .hasSize(1);
        NativeLifecycleInstrumentation.startupInstrumentation();
    }

    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "R1")
    void controlR1_invalidModeRejectedByPolicy() throws ClassNotFoundException {
        assertRejectedWithoutNativeActivity(
                Map.of(MODE, "prod"),
                Stage.POLICY, Reason.MODE_REJECTED);
    }

    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "R2")
    void controlR2_ciTargetMismatchRejectedByInitializer() throws ClassNotFoundException {
        assertRejectedWithoutNativeActivity(
                Map.of(MODE, "ci",
                        "spring.datasource.url",
                        "jdbc:postgresql://synthetic-other.invalid:15432/synthetic_other_db",
                        "spring.datasource.username", "jtm_test"),
                Stage.INITIALIZER, Reason.TARGET_MISMATCH);
    }

    /** The initializer adds its fixed timeout first; the conflicting lower-precedence value is still rejected. */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "R3")
    void controlR3_conflictingTimeoutRejectedDespiteFixedTimeoutSource() throws ClassNotFoundException {
        assertRejectedWithoutNativeActivity(
                Map.of(MODE, "ci",
                        "spring.datasource.url", "jdbc:postgresql://localhost:5432/task_manager_test",
                        "spring.datasource.username", "jtm_test",
                        "spring.datasource.hikari.connection-timeout", "10001"),
                Stage.INITIALIZER, Reason.DRIVER_TIMEOUT_REJECTED);
    }

    private static void assertRejectedWithoutNativeActivity(Map<String, Object> properties, Stage stage,
            Reason reason) throws ClassNotFoundException {
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(ALL_POINTS, ALL_POINTS);
        Session session = installed.session();
        GenericApplicationContext context = null;
        Throwable primary = null;
        try {
            ClassLoader loader = GuardedTestDatabaseNativeRejectionTest.class.getClassLoader();
            Class.forName(HIKARI, false, loader);
            Class.forName(PG_DRIVER, false, loader);
            NativeLifecycleObservation.DiagnosticSnapshot diagnostics = session.diagnostics();
            assertThat(diagnostics.invalidReasons()).as("invalid reasons after load").isEmpty();
            assertThat(diagnostics.transformedTypes()).containsEntry(HIKARI, 1).containsEntry(PG_DRIVER, 1);
            assertThat(diagnostics.armed()).containsExactlyInAnyOrderElementsOf(ALL_POINTS);
            assertThat(diagnostics.events()).as("events before initializer").isEmpty();

            context = isolatedContext(properties);
            GenericApplicationContext target = context;
            Throwable thrown = catchThrowable(() -> new GuardedTestDatabaseInitializer().initialize(target));
            assertThat(thrown).isInstanceOf(TestDatabaseGuardException.class);
            TestDatabaseGuardException rejection = (TestDatabaseGuardException) thrown;
            assertThat(rejection.stage()).isEqualTo(stage);
            assertThat(rejection.reason()).isEqualTo(reason);
            assertThat(context.isActive()).as("context never refreshed").isFalse();
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            finishAfterCleanup(installed, context, primary);
        }
    }

    /** Closes the context, then finishes observation; cleanup problems fail the test and are never discarded. */
    private static void finishAfterCleanup(NativeLifecycleInstrumentation installed, GenericApplicationContext context,
            Throwable primary) {
        List<Throwable> problems = new ArrayList<>();
        if (context != null) {
            try {
                context.close();
            } catch (Throwable failure) {
                problems.add(failure);
            }
        }
        FinalResult result = null;
        try {
            result = installed.finish();
        } catch (Throwable failure) {
            problems.add(failure);
        }
        if (result != null) {
            if (installed.cleanupRuns() != 1) {
                problems.add(new AssertionError("cleanup runs: " + installed.cleanupRuns()));
            }
            if (result.status() != Status.VALID) {
                problems.add(new AssertionError("observation INVALID: " + result.invalidReasons()));
            } else {
                // Accepted counts exist only for a VALID result.
                expectZero(result, Point.PG_DRIVER_CONNECT, Kind.ENTRY, problems);
                expectZero(result, Point.HIKARI_GET_CONNECTION_NO_ARG, Kind.ENTRY, problems);
                expectZero(result, Point.HIKARI_NO_ARG_CONSTRUCTOR, Kind.CONSTRUCTOR_COMPLETED, problems);
                expectZero(result, Point.HIKARI_CLOSE, Kind.ENTRY, problems);
            }
        } else if (problems.isEmpty()) {
            problems.add(new AssertionError("no final observation result"));
        }
        if (problems.isEmpty()) {
            return;
        }
        if (primary != null) {
            problems.forEach(primary::addSuppressed);
            return;
        }
        AssertionError failure = new AssertionError("cleanup/observation failed: " + problems.size() + " problem(s)");
        problems.forEach(failure::addSuppressed);
        throw failure;
    }

    private static void expectZero(FinalResult result, Point point, Kind kind, List<Throwable> problems) {
        long count = result.acceptedCount(point, kind);
        if (count != 0) {
            problems.add(new AssertionError(point + " " + kind + " observed " + count + " time(s)"));
        }
    }

    /** Unrefreshed context whose environment holds only the explicit test properties. */
    private static GenericApplicationContext isolatedContext(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        MutablePropertySources sources = environment.getPropertySources();
        for (PropertySource<?> inherited : sources.stream().toList()) {
            sources.remove(inherited.getName());
        }
        sources.addFirst(new MapPropertySource("guardedNativeRejectionTestProperties", properties));
        GenericApplicationContext context = new GenericApplicationContext();
        context.setEnvironment(environment);
        return context;
    }
}
