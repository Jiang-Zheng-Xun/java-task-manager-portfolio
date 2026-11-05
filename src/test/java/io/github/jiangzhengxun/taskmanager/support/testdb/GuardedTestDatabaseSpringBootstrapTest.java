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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestContextBootstrapper;
import org.springframework.test.context.TestContextManager;

/**
 * Annotation-driven bootstrap controls. Each control is enabled only when
 * {@code -Dtaskmanager.guardedSpringBootstrap.control=<id>} names it, so a default suite reports every control as
 * skipped (never passed). Exactly one control is meant to run per fresh JVM, with the reviewed startup agent.
 *
 * <p>Each control bootstraps a nested fixture through the real Spring TestContext framework
 * ({@link TestContextManager}), so {@link GuardedTestDatabase} selects the guard initializer and imports the controlled
 * configuration. The fixture also imports an unsupported {@link FactoryBean} definition, which
 * {@link TestDatabaseBeanDefinitionGuard} must reject as BEAN_DEFINITION / FACTORY_BEAN_REJECTED during refresh. A
 * registry post-processor probe (ordered after configuration-class parsing, so it runs before the context-added guard)
 * records metadata only: the policy snapshot, the imported definitions and that no guarded singleton exists yet.
 *
 * <p>Not covered: a successful refresh, identity verification, Flyway/JPA ordering and real database access. Constructor
 * observation covers normal completion only. The fixtures are nested in this test class, so the test-type exclude
 * filter keeps them out of other tests' component scans.
 */
class GuardedTestDatabaseSpringBootstrapTest {

    private static final String CONTROL = "taskmanager.guardedSpringBootstrap.control";
    private static final String HIKARI = "com.zaxxer.hikari.HikariDataSource";
    private static final String PG_DRIVER = "org.postgresql.Driver";
    private static final Set<Point> ALL_POINTS = EnumSet.allOf(Point.class);
    private static final String STRATEGY_FACTORY_METHOD = "guardedFlywayMigrationStrategy";

    /** An explicitly enabled run must name a known control and must have exactly one startup agent. */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = ".*")
    void enabledRunHasKnownControlAndStartupAgent() {
        assertThat(System.getProperty(CONTROL)).matches("SB|JPA");
        assertThat(ManagementFactory.getRuntimeMXBean().getInputArguments())
                .filteredOn(argument -> argument.startsWith("-javaagent:"))
                .hasSize(1);
        NativeLifecycleInstrumentation.startupInstrumentation();
    }

    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "SB")
    void controlSB_springBootTestBootstrapRejectsFactoryBeanDefinition() throws ClassNotFoundException {
        assertBootstrapRejected(SpringBootTestFixture.class,
                "org.springframework.boot.test.context.SpringBootTestContextBootstrapper");
    }

    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "JPA")
    void controlJPA_dataJpaTestBootstrapRejectsFactoryBeanDefinition() throws ClassNotFoundException {
        assertBootstrapRejected(DataJpaTestFixture.class,
                "org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTestContextBootstrapper");
    }

    private static void assertBootstrapRejected(Class<?> fixture, String expectedBootstrapper)
            throws ClassNotFoundException {
        NativeLifecycleInstrumentation installed = NativeLifecycleInstrumentation.install(ALL_POINTS, ALL_POINTS);
        Session session = installed.session();
        AtomicReference<TestContext> testContext = new AtomicReference<>();
        AtomicReference<ApplicationContext> loaded = new AtomicReference<>();
        Throwable primary = null;
        try {
            ClassLoader loader = GuardedTestDatabaseSpringBootstrapTest.class.getClassLoader();
            Class.forName(HIKARI, false, loader);
            Class.forName(PG_DRIVER, false, loader);
            NativeLifecycleObservation.DiagnosticSnapshot diagnostics = session.diagnostics();
            assertThat(diagnostics.invalidReasons()).as("invalid reasons after load").isEmpty();
            assertThat(diagnostics.transformedTypes()).containsEntry(HIKARI, 1).containsEntry(PG_DRIVER, 1);
            assertThat(diagnostics.armed()).containsExactlyInAnyOrderElementsOf(ALL_POINTS);
            assertThat(diagnostics.events()).as("events before bootstrap").isEmpty();

            // Bootstrap selection: this exact instance is the one the TestContextManager below starts from.
            TestContextBootstrapper bootstrapper = BootstrapUtils.resolveTestContextBootstrapper(fixture);
            assertThat(bootstrapper.getClass().getName()).isEqualTo(expectedBootstrapper);
            MergedContextConfiguration merged = bootstrapper.buildMergedContextConfiguration();
            assertThat(merged.getContextInitializerClasses()).contains(GuardedTestDatabaseInitializer.class);

            testContext.set(new TestContextManager(bootstrapper).getTestContext());
            // Loaded once; a failed load is never retried. A returned context is kept for cleanup.
            Throwable thrown = catchThrowable(() -> loaded.set(testContext.get().getApplicationContext()));
            assertThat(loaded.get()).as("unexpected successful bootstrap").isNull();
            List<TestDatabaseGuardException> rejections = guardRejections(thrown);
            assertThat(rejections).as("guard rejections in the cause chain of %s", thrown).hasSize(1);
            assertThat(rejections.get(0).stage()).isEqualTo(Stage.BEAN_DEFINITION);
            assertThat(rejections.get(0).reason()).isEqualTo(Reason.FACTORY_BEAN_REJECTED);

            // Recorded during the real refresh, after configuration parsing and before the guard ran.
            Map<String, Object> facts = Probe.FACTS.get();
            assertThat(facts).as("probe ran during refresh").isNotNull();
            assertThat(facts).containsEntry("snapshotType", TestDatabasePolicyProvider.Resolved.class.getName())
                    .containsEntry("snapshotIsRecord", true)
                    .containsEntry("snapshotMode", TestDatabaseMode.CI)
                    .containsEntry("snapshotHasDefinition", false)
                    .containsEntry("guardedConfigurationDefinitions", 1)
                    .containsEntry("strategyDefinitions", 1)
                    .containsEntry("fixtureFactoryBeanDefinitions", 1)
                    .containsEntry("strategySingletonCreated", false)
                    .containsEntry("fixtureSingletonCreated", false);
            assertThat(RejectedFactoryBean.CONSTRUCTED.get()).as("fixture constructor").isZero();
            assertThat(RejectedFactoryBean.GET_OBJECT.get()).as("fixture getObject").isZero();
            assertThat(RejectedFactoryBean.GET_OBJECT_TYPE.get()).as("fixture getObjectType").isZero();
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            finishAfterCleanup(installed, testContext.get(), loaded.get(), primary);
        }
    }

    private static List<TestDatabaseGuardException> guardRejections(Throwable thrown) {
        List<TestDatabaseGuardException> found = new ArrayList<>();
        for (Throwable cause = thrown; cause != null && found.size() < 8; cause = cause.getCause()) {
            if (cause instanceof TestDatabaseGuardException rejection) {
                found.add(rejection);
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return found;
    }

    /**
     * Context and cache cleanup first, then observation finish (attempted even if context cleanup failed). A returned
     * context is removed from the TestContext cache, which closes it; a failed load left nothing cached and is only
     * checked, never loaded again. Problems fail the test and are never discarded.
     */
    private static void finishAfterCleanup(NativeLifecycleInstrumentation installed, TestContext testContext,
            ApplicationContext loaded, Throwable primary) {
        List<Throwable> problems = new ArrayList<>();
        if (testContext != null) {
            try {
                if (loaded != null || testContext.hasApplicationContext()) {
                    testContext.markApplicationContextDirty(null);
                }
                if (testContext.hasApplicationContext()) {
                    problems.add(new AssertionError("context still cached after cleanup"));
                }
            } catch (Throwable failure) {
                problems.add(failure);
            }
        }
        try {
            if (loaded instanceof ConfigurableApplicationContext configurable && configurable.isActive()) {
                configurable.close();
                problems.add(new AssertionError("returned context was still active after cache removal"));
            }
        } catch (Throwable failure) {
            problems.add(failure);
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

    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
            "TEST_DB_MODE=ci",
            "spring.datasource.url=jdbc:postgresql://localhost:5432/task_manager_test",
            "spring.datasource.username=jtm_test"})
    @GuardedTestDatabase
    @Import({ProbeConfiguration.class, RejectedFactoryBean.class})
    static class SpringBootTestFixture {
    }

    @DataJpaTest(properties = {
            "TEST_DB_MODE=ci",
            "spring.datasource.url=jdbc:postgresql://localhost:5432/task_manager_test",
            "spring.datasource.username=jtm_test"})
    @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
    @GuardedTestDatabase
    @Import({ProbeConfiguration.class, RejectedFactoryBean.class})
    static class DataJpaTestFixture {
    }

    /** Deliberately unsupported definition; every entry point counts so the test can prove none was reached. */
    static class RejectedFactoryBean implements FactoryBean<String> {

        static final AtomicInteger CONSTRUCTED = new AtomicInteger();
        static final AtomicInteger GET_OBJECT = new AtomicInteger();
        static final AtomicInteger GET_OBJECT_TYPE = new AtomicInteger();

        RejectedFactoryBean() {
            CONSTRUCTED.incrementAndGet();
        }

        @Override
        public String getObject() {
            GET_OBJECT.incrementAndGet();
            return "rejected";
        }

        @Override
        public Class<?> getObjectType() {
            GET_OBJECT_TYPE.incrementAndGet();
            return String.class;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        static Probe guardedSpringBootstrapProbe() {
            return new Probe();
        }
    }

    /**
     * Metadata-only probe. Ordered (not PriorityOrdered), so it runs after configuration-class parsing; registry
     * post-processors' postProcessBeanFactory runs before the context-added guard. It never fetches a bean.
     */
    static final class Probe implements BeanDefinitionRegistryPostProcessor, Ordered {

        static final AtomicReference<Map<String, Object>> FACTS = new AtomicReference<>();

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            Map<String, Object> facts = new LinkedHashMap<>();
            Object snapshot = beanFactory.getSingleton(GuardedTestDatabaseInitializer.POLICY_SNAPSHOT_NAME);
            facts.put("snapshotType", snapshot == null ? null : snapshot.getClass().getName());
            facts.put("snapshotIsRecord", snapshot != null && snapshot.getClass().isRecord());
            facts.put("snapshotMode",
                    snapshot instanceof TestDatabasePolicyProvider.Resolved resolved ? resolved.mode() : null);
            facts.put("snapshotHasDefinition",
                    beanFactory.containsBeanDefinition(GuardedTestDatabaseInitializer.POLICY_SNAPSHOT_NAME));
            int configurations = 0;
            int strategies = 0;
            int fixtures = 0;
            boolean strategyCreated = false;
            boolean fixtureCreated = false;
            for (String name : beanFactory.getBeanDefinitionNames()) {
                BeanDefinition definition = beanFactory.getBeanDefinition(name);
                String className = definition.getBeanClassName();
                if (GuardedTestDatabaseConfiguration.class.getName().equals(className)) {
                    configurations++;
                }
                if (RejectedFactoryBean.class.getName().equals(className)) {
                    fixtures++;
                    fixtureCreated |= beanFactory.containsSingleton(name);
                }
                String factoryBeanName = definition.getFactoryBeanName();
                if (STRATEGY_FACTORY_METHOD.equals(definition.getFactoryMethodName()) && factoryBeanName != null
                        && beanFactory.containsBeanDefinition(factoryBeanName)
                        && GuardedTestDatabaseConfiguration.class.getName()
                                .equals(beanFactory.getBeanDefinition(factoryBeanName).getBeanClassName())) {
                    strategies++;
                    strategyCreated |= beanFactory.containsSingleton(name);
                }
            }
            facts.put("guardedConfigurationDefinitions", configurations);
            facts.put("strategyDefinitions", strategies);
            facts.put("fixtureFactoryBeanDefinitions", fixtures);
            facts.put("strategySingletonCreated", strategyCreated);
            facts.put("fixtureSingletonCreated", fixtureCreated);
            FACTS.set(facts);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
