package io.github.jiangzhengxun.taskmanager.support.testdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import jakarta.persistence.EntityManagerFactory;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.ReplaceOverride;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.ApplicationContextFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.autoconfigure.condition.ConditionEvaluationReport;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.SpringBootContextLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.ResolvableType;
import org.springframework.core.type.MethodMetadata;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.MergedContextConfiguration;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestContextBootstrapper;
import org.springframework.test.context.TestContextManager;

/**
 * Actual Boot bootstrap definition checks with an EXPLICIT loader and initializer. Property
 * {@code taskmanager.guardedBootDefinition.control}: absent disables every control; an exact control ID enables it; any
 * other value, including empty, fails {@link #enabledRunHasKnownControl()} before any bootstrap. Run one control per
 * fresh JVM (Spring's context failure threshold counts failed loads per configuration).
 *
 * <p>Each fixture directly declares {@code @ContextConfiguration(loader = RecordingBootContextLoader.class,
 * initializers = GuardedTestDatabaseInitializer.class)}; {@link GuardedTestDatabase} is kept for its configuration
 * import only. CLAIM BOUNDARY: these controls verify explicitly configured initializer integration, the actual Boot
 * definitions seen by the unchanged guard and attribution of their OWN rejection. They do NOT verify meta-annotation
 * initializer propagation, historical C2 attribution, successful JPA startup, migration ordering or native connection
 * absence. Creation interception is fixed-fixture bean-factory prevention, not a network barrier.
 */
class GuardedTestDatabaseBootDefinitionAttributionTest {

    private static final String CONTROL = "taskmanager.guardedBootDefinition.control";
    private static final Set<String> CONTROLS = Set.of("SB_COMPAT", "JPA_COMPAT", "SB_ATTRIB", "JPA_ATTRIB");
    private static final String SB_BOOTSTRAPPER = "org.springframework.boot.test.context.SpringBootTestContextBootstrapper";
    private static final String JPA_BOOTSTRAPPER =
            "org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTestContextBootstrapper";
    private static final String GUARD_METHOD = "requireVerifiableDefinitions";
    private static final String EMF = TestDatabaseBeanDefinitionGuard.BOOT_JPA_FACTORY_BEAN_NAME;
    private static final String FIXTURE = RejectedFactoryBean.class.getName();
    private static final Set<String> PROTECTED_NAMES = Set.of("dataSource", "jdbcConnectionDetails", "flyway",
            "flywayConnectionDetails", "flywayInitializer", "guardedFlywayMigrationStrategy", EMF, FIXTURE);
    private static final List<Class<?>> PROTECTED_TYPES = List.of(DataSource.class, Flyway.class,
            FlywayMigrationInitializer.class, FactoryBean.class, EntityManagerFactory.class,
            GuardedFlywayMigrationStrategy.class);
    // Boot 3.5.16 AutoConfigurationPackages.register: RootBeanDefinition(BasePackages.class), ROLE_INFRASTRUCTURE,
    // constructor argument 0 = String[] package names (BOOT6 retrieved source sha256 882aa55a..., lines 93-118).
    static final String AUTO_CONFIGURATION_PACKAGES_BEAN = "org.springframework.boot.autoconfigure.AutoConfigurationPackages";
    static final String BASE_PACKAGES_CLASS = "org.springframework.boot.autoconfigure.AutoConfigurationPackages$BasePackages";
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final ThreadLocal<Handle> HANDLE = new ThreadLocal<>();

    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = ".*")
    void enabledRunHasKnownControl() {
        if (!CONTROLS.contains(System.getProperty(CONTROL))) {
            throw new AssertionError("CONTROL:UNKNOWN");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "SB_COMPAT")
    void controlSbCompat() {
        run(SbCompatFixture.class, SB_BOOTSTRAPPER, false);
    }

    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "JPA_COMPAT")
    void controlJpaCompat() {
        run(JpaCompatFixture.class, JPA_BOOTSTRAPPER, false);
    }

    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "SB_ATTRIB")
    void controlSbAttrib() {
        run(SbAttribFixture.class, SB_BOOTSTRAPPER, true);
    }

    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "JPA_ATTRIB")
    void controlJpaAttrib() {
        run(JpaAttribFixture.class, JPA_BOOTSTRAPPER, true);
    }

    private static void run(Class<?> fixture, String expectedBootstrapper, boolean attrib) {
        RejectedFactoryBean.reset();
        if (HANDLE.get() != null) {
            throw new AssertionError("HANDLE:REUSED");
        }
        Handle handle = new Handle();
        handle.controlId = System.getProperty(CONTROL);
        HANDLE.set(handle);
        TestContext testContext = null;
        Throwable primary = null;
        try {
            TestContextBootstrapper bootstrapper = BootstrapUtils.resolveTestContextBootstrapper(fixture);
            assertThat(bootstrapper.getClass().getName()).isEqualTo(expectedBootstrapper);
            MergedContextConfiguration merged = bootstrapper.buildMergedContextConfiguration();
            assertThat(merged.getContextLoader()).isInstanceOf(RecordingBootContextLoader.class);
            // A Set cannot show duplicates; a duplicate registration would be rejected by the initializer itself.
            assertThat(merged.getContextInitializerClasses()).containsExactly(GuardedTestDatabaseInitializer.class);
            testContext = new TestContextManager(bootstrapper).getTestContext();
            TestContext target = testContext;
            Throwable thrown = catchThrowable(target::getApplicationContext);
            // Terminal marker: the single load attempt has ended (ATTRIB or any failure). Observation boundary: this
            // does not instrument the instant the private guard method throws.
            handle.terminal = true;
            // Pre-cleanup diagnostics: after the single load attempt, before any assertion can end the test.
            emitDiagnosticsSafely("PRE_CLEANUP", handle, System.out::println);
            assertThat(thrown).as("bootstrap must stop").isNotNull();
            assertThat(handle.created).as("contexts created by loader").isEqualTo(1);
            assertThat(handle.webType).isEqualTo(WebApplicationType.NONE);
            assertThat(handle.violations).as("forbidden creation attempts").isEmpty();
            assertThat(causes(thrown, CreationViolation.class)).isEmpty();
            assertThat(handle.foreignFactory).as("sentinel/probe saw a foreign bean factory").isFalse();
            assertThat(handle.probeRan).as("probe ran before guard").isTrue();
            assertThat(handle.traceErrors).as("trace errors").isEmpty();
            assertNoProtectedSingletons(handle);
            if (attrib) {
                assertAttribution(handle, thrown);
            }
            else {
                assertCompatibility(handle, thrown);
            }
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            cleanup(handle, testContext, primary, System.out::println);
        }
    }

    private static void assertAttribution(Handle handle, Throwable thrown) {
        List<TestDatabaseGuardException> rejections = causes(thrown, TestDatabaseGuardException.class);
        assertThat(rejections).hasSize(1);
        assertThat(rejections.get(0).stage()).isEqualTo(Stage.BEAN_DEFINITION);
        assertThat(rejections.get(0).reason()).isEqualTo(Reason.FACTORY_BEAN_REJECTED);
        assertThat(causes(thrown, SentinelStop.class)).isEmpty();
        assertThat(handle.sentinelReached).isFalse();
        assertThat(handle.fixtureDefined).as("fixture is a definition").isTrue();
        assertThat(handle.manualSingletons).doesNotContain(FIXTURE);
        List<String> visited = completeTrace(handle);
        int index = handle.snapshot.indexOf(FIXTURE);
        assertThat(index).as("fixture in snapshot").isGreaterThanOrEqualTo(0);
        assertThat(visited).as("trace is the exact snapshot prefix ending at the fixture")
                .containsExactlyElementsOf(handle.snapshot.subList(0, index + 1));
        assertThat(handle.lastResult).as("last RETURN(fixture, true)").isTrue();
        assertThat(RejectedFactoryBean.CONSTRUCTED.get()).isZero();
        assertThat(RejectedFactoryBean.GET_OBJECT.get()).isZero();
        assertThat(RejectedFactoryBean.GET_OBJECT_TYPE.get()).isZero();
        System.out.println("EMF_POSITION=" + (handle.returnedTrue.contains(EMF) ? "PASSED_EARLIER" : "NOT_REACHED"));
    }

    private static void assertCompatibility(Handle handle, Throwable thrown) {
        assertThat(causes(thrown, SentinelStop.class)).as("exact sentinel boundary").hasSize(1);
        assertThat(causes(thrown, TestDatabaseGuardException.class)).as("no guard rejection").isEmpty();
        assertThat(handle.sentinelReached).isTrue();
        assertThat(handle.emfPresent).isTrue();
        assertThat(handle.emfDeclaringClass).isEqualTo(TestDatabaseBeanDefinitionGuard.BOOT_JPA_BASE_CONFIGURATION_CLASS);
        assertThat(handle.emfMethod).isEqualTo(EMF);
        assertThat(handle.emfReturnType).isEqualTo(TestDatabaseBeanDefinitionGuard.BOOT_JPA_FACTORY_CLASS);
        assertThat(handle.emfStatic).isFalse();
        assertThat(handle.emfConfigurationClass)
                .isEqualTo(TestDatabaseBeanDefinitionGuard.BOOT_HIBERNATE_JPA_CONFIGURATION_CLASS);
        assertThat(handle.emfFactoryType).isEqualTo(TestDatabaseBeanDefinitionGuard.BOOT_JPA_FACTORY_CLASS);
        assertThat(handle.emfRecognized).isTrue();
        List<String> visited = completeTrace(handle);
        assertThat(visited).as("full non-abstract snapshot").containsExactlyElementsOf(handle.snapshot);
        assertThat(handle.returnedTrue).as("EMF RETURN(true)").contains(EMF);
        assertThat(handle.fixtureDefined).isFalse();
    }

    /** Paired ENTRY/RETURN only, no THROW, no calls after the stop; returns the DIRECT names in order. */
    private static List<String> completeTrace(Handle handle) {
        assertThat(handle.snapshot).as("snapshot taken at first DIRECT call").isNotNull();
        assertThat(handle.events).as("DIRECT events").isNotEmpty();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < handle.events.size(); i += 2) {
            assertThat(i + 1).as("unpaired DIRECT entry").isLessThan(handle.events.size());
            String entry = handle.events.get(i);
            String exit = handle.events.get(i + 1);
            assertThat(entry).startsWith("ENTRY:");
            String name = entry.substring("ENTRY:".length());
            assertThat(exit).as("RETURN for " + names.size()).startsWith("RETURN:" + name + ":");
            names.add(name);
        }
        assertThat(handle.callsAfterStop).as("DIRECT calls after stop").isZero();
        return names;
    }

    private static void assertNoProtectedSingletons(Handle handle) {
        DefaultListableBeanFactory beanFactory = handle.beanFactory;
        assertThat(beanFactory).isNotNull();
        for (String name : PROTECTED_NAMES) {
            assertThat(beanFactory.containsSingleton(name)).as("protected singleton " + name).isFalse();
        }
        assertThat(handle.manualSingletons).as("protected manual singletons").doesNotContainAnyElementsOf(PROTECTED_NAMES);
    }

    private static void cleanup(Handle handle, TestContext testContext, Throwable primary, Consumer<String> sink) {
        List<String> problems = new ArrayList<>();
        if (testContext != null) {
            try {
                if (testContext.hasApplicationContext()) {
                    testContext.markApplicationContextDirty(null);
                }
            } catch (Throwable failure) {
                problems.add("CLEANUP:MARK_DIRTY_FAILED");
            }
            try {
                if (testContext.hasApplicationContext()) {
                    problems.add("CLEANUP:CONTEXT_STILL_CACHED");
                }
            } catch (Throwable failure) {
                problems.add("CLEANUP:CACHE_CHECK_FAILED");
            }
        }
        ConfigurableApplicationContext context = handle.context;
        if (context != null) {
            try {
                if (context.isActive()) {
                    problems.add("CLEANUP:RETAINED_CONTEXT_ACTIVE");
                    context.close();
                }
            } catch (Throwable failure) {
                problems.add("CLEANUP:CLOSE_FAILED");
            }
            try {
                if (context.isActive()) {
                    problems.add("CLEANUP:CONTEXT_STILL_ACTIVE");
                }
            } catch (Throwable failure) {
                problems.add("CLEANUP:FINAL_STATE_CHECK_FAILED");
            }
        }
        // Final observation AFTER every cleanup attempt: catches violations recorded during cleanup even if Spring
        // swallowed the original interception exception. ThreadLocal is removed regardless.
        try {
            problems.addAll(finalObservationProblems(handle));
        } catch (Throwable failure) {
            problems.add("FINAL:CHECK_FAILED");
        } finally {
            HANDLE.remove();
        }
        if (HANDLE.get() != null) {
            problems.add("CLEANUP:THREAD_LOCAL_NOT_REMOVED");
        }
        // Post-cleanup diagnostics after every cleanup attempt; diagnostic failures are reported like cleanup problems.
        emitDiagnosticsSafely("POST_CLEANUP", handle, sink);
        problems.addAll(handle.diagnosticFailures);
        if (problems.isEmpty()) {
            return;
        }
        if (primary != null) {
            problems.forEach(label -> primary.addSuppressed(new AssertionError(label)));
            return;
        }
        AssertionError failure = new AssertionError("CLEANUP:FAILED");
        problems.forEach(label -> failure.addSuppressed(new AssertionError(label)));
        throw failure;
    }

    /** Production-used final validation (called from cleanup); fixed labels only. */
    static List<String> finalObservationProblems(Handle handle) {
        List<String> problems = new ArrayList<>();
        if (!handle.violations.isEmpty()) {
            problems.add("FINAL:VIOLATIONS");
        }
        if (handle.callsAfterStop != 0) {
            problems.add("FINAL:DIRECT_AFTER_TERMINAL");
        }
        if (!handle.traceErrors.isEmpty()) {
            problems.add("FINAL:TRACE_ERRORS");
        }
        if (handle.beanFactory != null) {
            for (String name : PROTECTED_NAMES) {
                if (handle.beanFactory.containsSingleton(name)) {
                    problems.add("FINAL:PROTECTED_SINGLETON");
                    break;
                }
            }
        }
        return problems;
    }

    // ---- Recorder diagnostics: evidence only, never an acceptance condition ----

    static final String DIAG_PREFIX = "BOOT10_DIAG v1 ";
    static final String UNAVAILABLE = "UNAVAILABLE";
    static final String DEFINITION_READ_FAILED = "definition=READ_FAILED";

    /**
     * Never throws: a failure becomes a fixed label that cleanup reports (suppressed on an existing primary failure,
     * otherwise CLEANUP:FAILED), so it can neither replace the primary failure nor let a control pass silently.
     */
    static void emitDiagnosticsSafely(String phase, Handle handle, Consumer<String> sink) {
        try {
            for (String line : diagnosticLines(phase, handle)) {
                sink.accept(line);
            }
        } catch (Throwable failure) {
            handle.diagnosticFailures.add("DIAG:" + phase + ":FAILED");
        }
    }

    /**
     * Recorder values and structural fields only; never toString of definitions, values, suppliers or exceptions.
     * terminal is the existing marker set after the load attempt, not the instant the guard throws.
     */
    static List<String> diagnosticLines(String phase, Handle handle) {
        List<String> lines = new ArrayList<>();
        Consumer<String> out = field -> lines.add(DIAG_PREFIX + phase + " " + field);
        out.accept("BEGIN");
        out.accept("control=" + orUnavailable(handle.controlId));
        out.accept("created=" + handle.created);
        out.accept("terminal=" + handle.terminal);
        out.accept("sentinelReached=" + handle.sentinelReached);
        out.accept("foreignFactory=" + handle.foreignFactory);
        out.accept("probeRan=" + handle.probeRan);
        out.accept("fixtureDefined=" + handle.fixtureDefined);
        out.accept("emf.present=" + handle.emfPresent);
        out.accept("emf.declaringClass=" + orUnavailable(handle.emfDeclaringClass));
        out.accept("emf.method=" + orUnavailable(handle.emfMethod));
        out.accept("emf.returnType=" + orUnavailable(handle.emfReturnType));
        out.accept("emf.static=" + handle.emfStatic);
        out.accept("emf.configurationClass=" + orUnavailable(handle.emfConfigurationClass));
        out.accept("emf.factoryType=" + orUnavailable(handle.emfFactoryType));
        out.accept("emf.recognized=" + handle.emfRecognized);
        if (handle.snapshot == null) {
            out.accept("snapshot.present=false");
        }
        else {
            out.accept("snapshot.present=true");
            indexed(out, "snapshot", handle.snapshot);
        }
        out.accept("direct.observed=" + !handle.events.isEmpty());
        indexed(out, "event", handle.events);
        out.accept("lastResult=" + (handle.lastResult == null ? "NONE" : handle.lastResult));
        out.accept("nestedCalls=" + handle.nestedCalls);
        out.accept("callsAfterTerminal=" + handle.callsAfterStop);
        indexed(out, "returnedTrue", handle.returnedTrue);
        indexed(out, "permittedMetadata", handle.permittedMetadata);
        indexed(out, "permittedPackages", handle.permittedPackages);
        indexed(out, "manualSingletons", handle.manualSingletons);
        indexed(out, "violations", handle.violations);
        indexed(out, "postTerminalReports", handle.postTerminalReports);
        indexed(out, "traceErrors", handle.traceErrors);
        indexed(out, "diagnosticFailures", handle.diagnosticFailures);
        // Three distinct states: captured (possibly empty), boundary reached but capture failed, no boundary.
        if (handle.firstDirectSingletons != null) {
            out.accept("firstDirectSingletons=CAPTURED");
            indexed(out, "firstDirectSingletons", handle.firstDirectSingletons);
        }
        else if (handle.firstDirectCaptureFailed) {
            out.accept("firstDirectSingletons=CAPTURE_FAILED");
        }
        else {
            out.accept("firstDirectSingletons=NO_DIRECT_BOUNDARY");
        }
        if (handle.firstDirectBpps != null) {
            out.accept("firstDirectBpps=CAPTURED");
            out.accept("firstDirectBpps.boundary=FIRST_INSTRUMENTED_DIRECT_ISFACTORYBEAN_BEFORE_DELEGATION");
            indexed(out, "firstDirectBpps", handle.firstDirectBpps);
            out.accept("firstDirectLoadTimeWeaver.definition=" + handle.firstDirectLtwDefinition);
            out.accept("firstDirectLoadTimeWeaver.singleton=" + handle.firstDirectLtwSingleton);
        }
        else if (handle.firstDirectBppCaptureFailed) {
            out.accept("firstDirectBpps=CAPTURE_FAILED");
        }
        else {
            out.accept("firstDirectBpps=NO_DIRECT_BOUNDARY");
        }
        List<String> directNames = new ArrayList<>(new LinkedHashSet<>(handle.events.stream()
                .filter(event -> event.startsWith("ENTRY:")).map(event -> event.substring("ENTRY:".length())).toList()));
        if (directNames.isEmpty()) {
            out.accept("definitions=NO_DIRECT_NAMES");
        }
        for (int i = 0; i < directNames.size(); i++) {
            List<String> summary = definitionSummary(handle.beanFactory, directNames.get(i));
            // Only an actual read exception yields READ_FAILED; NONE/UNAVAILABLE/ABSENT are ordinary states.
            String readFailure = "DIAG:" + phase + ":DEFINITION_READ_FAILED";
            if (summary.contains(DEFINITION_READ_FAILED) && !handle.diagnosticFailures.contains(readFailure)) {
                handle.diagnosticFailures.add(readFailure);
            }
            for (String field : summary) {
                out.accept("definition[" + i + "]." + field);
            }
        }
        out.accept("END");
        return lines;
    }

    /**
     * REGISTERED definition only: no merge, getBean, getType, isFactoryBean, FactoryBean method or supplier call.
     * RootBeanDefinition.getTargetType() may expose a type Spring already cached on it; it does not predict one.
     */
    static List<String> definitionSummary(DefaultListableBeanFactory beanFactory, String name) {
        List<String> fields = new ArrayList<>();
        fields.add("name=" + name);
        if (beanFactory == null) {
            fields.add("definition=" + UNAVAILABLE);
            return fields;
        }
        try {
            if (!beanFactory.containsBeanDefinition(name)) {
                fields.add("definition=ABSENT");
                return fields;
            }
            BeanDefinition definition = beanFactory.getBeanDefinition(name);
            fields.add("registeredClass=" + definition.getClass().getName());
            fields.add("beanClassName=" + orNone(definition.getBeanClassName()));
            fields.add("abstract=" + definition.isAbstract());
            fields.add("parentName=" + orNone(definition.getParentName()));
            fields.add("factoryBeanName=" + orNone(definition.getFactoryBeanName()));
            fields.add("factoryMethodName=" + orNone(definition.getFactoryMethodName()));
            fields.add("role=" + definition.getRole());
            Class<?> target = definition instanceof RootBeanDefinition root ? root.getTargetType() : null;
            fields.add("targetType=" + (definition instanceof RootBeanDefinition
                    ? (target == null ? "NONE" : target.getName()) : UNAVAILABLE));
            fields.add("supplierPresent=" + (definition instanceof AbstractBeanDefinition abstractDefinition
                    ? String.valueOf(abstractDefinition.getInstanceSupplier() != null) : UNAVAILABLE));
            MethodMetadata method = definition instanceof AnnotatedBeanDefinition annotated
                    ? annotated.getFactoryMethodMetadata() : null;
            if (method == null) {
                fields.add("factoryMethodMetadata=" + (definition instanceof AnnotatedBeanDefinition ? "NONE" : UNAVAILABLE));
            }
            else {
                fields.add("factoryMethod.declaringClass=" + method.getDeclaringClassName());
                fields.add("factoryMethod.name=" + method.getMethodName());
                fields.add("factoryMethod.returnType=" + method.getReturnTypeName());
                fields.add("factoryMethod.static=" + method.isStatic());
            }
        } catch (RuntimeException failure) {
            fields.add(DEFINITION_READ_FAILED);
        }
        return fields;
    }

    /**
     * Shared by the recorder and the mechanism tests. One attempt, never retried; cache-only reads supplied by the
     * caller. On failure the singleton list stays null and the capture is marked failed (not NO_DIRECT_BOUNDARY).
     */
    static void recordFirstDirectSingletons(Handle handle, Supplier<String[]> singletonNames,
            Function<String, Object> cachedSingleton, Predicate<String> localDefinition) {
        try {
            List<String> singletons = new ArrayList<>();
            for (String singletonName : singletonNames.get()) {
                Object existing = cachedSingleton.apply(singletonName);
                singletons.add(singletonName + "|" + (existing == null ? UNAVAILABLE : existing.getClass().getName())
                        + "|localDefinition=" + localDefinition.test(singletonName));
            }
            handle.firstDirectSingletons = List.copyOf(singletons);
        } catch (RuntimeException failure) {
            handle.firstDirectCaptureFailed = true;
            handle.diagnosticFailures.add("DIAG:FIRST_DIRECT_SINGLETONS:FAILED");
        }
    }

    private static void indexed(Consumer<String> out, String label, List<String> values) {
        out.accept(label + ".size=" + values.size());
        for (int i = 0; i < values.size(); i++) {
            out.accept(label + "[" + i + "]=" + values.get(i));
        }
    }

    private static String orUnavailable(String value) {
        return value == null ? UNAVAILABLE : value;
    }

    private static String orNone(String value) {
        return value == null ? "NONE" : value;
    }

    // ---- SYNTHETIC diagnostics mechanics (same helpers as the controls; no bootstrap, no DB) ----

    @Test
    void diagnosticsListEventsInOrderWithStableLabels() {
        Handle handle = new Handle();
        handle.controlId = "SB_COMPAT";
        handle.snapshot = List.of("first", "second");
        handle.events.addAll(List.of("ENTRY:first", "RETURN:first:false", "ENTRY:second", "RETURN:second:true"));
        handle.lastResult = true;
        handle.firstDirectSingletons = List.of("existing|java.lang.Object|localDefinition=false");
        String p = DIAG_PREFIX + "PRE_CLEANUP ";
        List<String> lines = diagnosticLines("PRE_CLEANUP", handle);
        assertThat(lines).allMatch(line -> line.startsWith(p));
        assertThat(lines.get(0)).isEqualTo(p + "BEGIN");
        assertThat(lines.get(lines.size() - 1)).isEqualTo(p + "END");
        assertThat(lines).containsSubsequence(p + "control=SB_COMPAT", p + "snapshot.present=true",
                p + "snapshot.size=2", p + "snapshot[0]=first", p + "snapshot[1]=second", p + "direct.observed=true",
                p + "event.size=4", p + "event[0]=ENTRY:first", p + "event[1]=RETURN:first:false",
                p + "event[2]=ENTRY:second", p + "event[3]=RETURN:second:true", p + "lastResult=true",
                p + "firstDirectSingletons.size=1",
                p + "firstDirectSingletons[0]=existing|java.lang.Object|localDefinition=false",
                p + "definition[0].name=first", p + "definition[0].definition=" + UNAVAILABLE,
                p + "definition[1].name=second", p + "definition[1].definition=" + UNAVAILABLE);
    }

    @Test
    void diagnosticsRepresentAbsentSnapshotAndNoDirectObservations() {
        String p = DIAG_PREFIX + "POST_CLEANUP ";
        List<String> lines = diagnosticLines("POST_CLEANUP", new Handle());
        assertThat(lines).contains(p + "control=" + UNAVAILABLE, p + "snapshot.present=false",
                p + "direct.observed=false", p + "event.size=0", p + "lastResult=NONE",
                p + "firstDirectSingletons=NO_DIRECT_BOUNDARY", p + "definitions=NO_DIRECT_NAMES");
        assertThat(lines).noneMatch(line -> line.startsWith(p + "snapshot[") || line.startsWith(p + "definition["));
    }

    @Test
    void definitionSummaryAvoidsTypeBeanAndSupplierCalls() {
        AtomicInteger eager = new AtomicInteger();
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory() {
            @Override
            public Object getBean(String name) {
                eager.incrementAndGet();
                return super.getBean(name);
            }

            @Override
            public Class<?> getType(String name) {
                eager.incrementAndGet();
                return super.getType(name);
            }

            @Override
            public Class<?> getType(String name, boolean allowFactoryBeanInit) {
                eager.incrementAndGet();
                return super.getType(name, allowFactoryBeanInit);
            }

            @Override
            public boolean isFactoryBean(String name) {
                eager.incrementAndGet();
                return super.isFactoryBean(name);
            }

            @Override
            public BeanDefinition getMergedBeanDefinition(String name) {
                eager.incrementAndGet();
                return super.getMergedBeanDefinition(name);
            }
        };
        RejectedFactoryBean.reset();
        RootBeanDefinition definition = new RootBeanDefinition(RejectedFactoryBean.class);
        definition.setInstanceSupplier(() -> {
            throw new AssertionError("SUPPLIER:INVOKED");
        });
        beanFactory.registerBeanDefinition("synthetic", definition);
        eager.set(0);
        assertThat(definitionSummary(beanFactory, "synthetic")).containsExactly("name=synthetic",
                "registeredClass=" + RootBeanDefinition.class.getName(),
                "beanClassName=" + RejectedFactoryBean.class.getName(), "abstract=false", "parentName=NONE",
                "factoryBeanName=NONE", "factoryMethodName=NONE", "role=" + BeanDefinition.ROLE_APPLICATION,
                "targetType=NONE", "supplierPresent=true", "factoryMethodMetadata=" + UNAVAILABLE);
        assertThat(definitionSummary(beanFactory, "missing")).containsExactly("name=missing", "definition=ABSENT");
        assertThat(eager.get()).isZero();
        assertThat(beanFactory.getSingletonCount()).isZero();
        assertThat(RejectedFactoryBean.CONSTRUCTED.get()).isZero();
        assertThat(RejectedFactoryBean.GET_OBJECT.get()).isZero();
        assertThat(RejectedFactoryBean.GET_OBJECT_TYPE.get()).isZero();
    }

    @Test
    void diagnosticFailurePreservesExistingPrimaryFailure() {
        Consumer<String> failing = line -> {
            throw new IllegalStateException("SINK");
        };
        Handle handle = new Handle();
        emitDiagnosticsSafely("PRE_CLEANUP", handle, failing);
        assertThat(handle.diagnosticFailures).containsExactly("DIAG:PRE_CLEANUP:FAILED");
        AssertionError primary = new AssertionError("PRIMARY");
        cleanup(handle, null, primary, failing);
        assertThat(primary.getMessage()).isEqualTo("PRIMARY");
        assertThat(primary.getSuppressed()).extracting(Throwable::getMessage)
                .containsExactly("DIAG:PRE_CLEANUP:FAILED", "DIAG:POST_CLEANUP:FAILED");
        Throwable withoutPrimary = catchThrowable(() -> cleanup(new Handle(), null, null, failing));
        assertThat(withoutPrimary).isInstanceOf(AssertionError.class).hasMessage("CLEANUP:FAILED");
        assertThat(withoutPrimary.getSuppressed()).extracting(Throwable::getMessage)
                .containsExactly("DIAG:POST_CLEANUP:FAILED");
    }

    @Test
    void definitionReadFailureIsLabelledAndReachesCleanupDecision() {
        String p = DIAG_PREFIX + "PRE_CLEANUP ";
        Handle handle = failingDefinitionHandle();
        List<String> lines = new ArrayList<>();
        emitDiagnosticsSafely("PRE_CLEANUP", handle, lines::add);
        assertThat(lines).containsSubsequence(p + "definition[0].name=failing", p + "definition[0]." + DEFINITION_READ_FAILED,
                p + "definition[1].name=absentName", p + "definition[1].definition=ABSENT");
        assertThat(lines).noneMatch(line -> line.contains("SYNTHETIC"));
        assertThat(handle.diagnosticFailures).containsExactly("DIAG:PRE_CLEANUP:DEFINITION_READ_FAILED");
        AssertionError primary = new AssertionError("PRIMARY");
        cleanup(handle, null, primary, line -> {
        });
        assertThat(primary.getMessage()).isEqualTo("PRIMARY");
        assertThat(primary.getSuppressed()).extracting(Throwable::getMessage)
                .containsExactly("DIAG:PRE_CLEANUP:DEFINITION_READ_FAILED", "DIAG:POST_CLEANUP:DEFINITION_READ_FAILED");
        Throwable withoutPrimary = catchThrowable(() -> cleanup(failingDefinitionHandle(), null, null, line -> {
        }));
        assertThat(withoutPrimary).isInstanceOf(AssertionError.class).hasMessage("CLEANUP:FAILED");
        assertThat(withoutPrimary.getSuppressed()).extracting(Throwable::getMessage)
                .containsExactly("DIAG:POST_CLEANUP:DEFINITION_READ_FAILED");
    }

    @Test
    void firstDirectSingletonStatesAreDistinct() {
        String p = DIAG_PREFIX + "PRE_CLEANUP ";
        String none = p + "firstDirectSingletons=NO_DIRECT_BOUNDARY";
        String captured = p + "firstDirectSingletons=CAPTURED";
        String failedState = p + "firstDirectSingletons=CAPTURE_FAILED";
        assertThat(diagnosticLines("PRE_CLEANUP", new Handle())).contains(none).doesNotContain(captured, failedState);
        Handle empty = new Handle();
        new RecordingBeanFactory(empty).captureFirstDirectSingletons();
        assertThat(empty.firstDirectSingletons).isEmpty();
        assertThat(diagnosticLines("PRE_CLEANUP", empty)).containsSubsequence(captured, p + "firstDirectSingletons.size=0")
                .doesNotContain(none, failedState);
        assertThat(empty.diagnosticFailures).isEmpty();
        Handle failed = new Handle();
        recordFirstDirectSingletons(failed, () -> {
            throw new IllegalStateException("SYNTHETIC");
        }, name -> null, name -> false);
        assertThat(failed.firstDirectSingletons).isNull();
        assertThat(failed.firstDirectCaptureFailed).isTrue();
        assertThat(failed.diagnosticFailures).containsExactly("DIAG:FIRST_DIRECT_SINGLETONS:FAILED");
        assertThat(diagnosticLines("PRE_CLEANUP", failed)).contains(failedState).doesNotContain(none, captured);
        Throwable thrown = catchThrowable(() -> cleanup(failed, null, null, line -> {
        }));
        assertThat(thrown).isInstanceOf(AssertionError.class).hasMessage("CLEANUP:FAILED");
        assertThat(thrown.getSuppressed()).extracting(Throwable::getMessage)
                .containsExactly("DIAG:FIRST_DIRECT_SINGLETONS:FAILED");
    }

    /** Recording factory with one registered definition whose getBeanClassName() throws once armed. */
    private static Handle failingDefinitionHandle() {
        Handle handle = new Handle();
        handle.beanFactory = new RecordingBeanFactory(handle);
        FailingNameDefinition definition = new FailingNameDefinition();
        handle.beanFactory.registerBeanDefinition("failing", definition);
        definition.armed = true;
        handle.events.addAll(List.of("ENTRY:failing", "RETURN:failing:false", "ENTRY:absentName", "RETURN:absentName:false"));
        return handle;
    }

    /** Narrow synthetic failure source for definitionSummary; no bean class, supplier or FactoryBean involved. */
    static final class FailingNameDefinition extends GenericBeanDefinition {
        boolean armed;

        @Override
        public String getBeanClassName() {
            if (armed) {
                throw new IllegalStateException("SYNTHETIC");
            }
            return super.getBeanClassName();
        }
    }

    // ---- SYNTHETIC mechanics tests (always enabled; no bootstrap, no Boot evidence) ----

    @Test
    void finalValidationDetectsCleanupTimeViolation() {
        Handle handle = new Handle();
        handle.violations.add("CREATE:AFTER_TERMINAL:synthetic");
        assertThat(finalObservationProblems(handle)).contains("FINAL:VIOLATIONS");
    }

    @Test
    void finalValidationDetectsDirectCallAfterTerminal() {
        Handle handle = new Handle();
        handle.callsAfterStop = 1;
        assertThat(finalObservationProblems(handle)).contains("FINAL:DIRECT_AFTER_TERMINAL");
    }

    @Test
    void finalValidationIsCleanForUntouchedHandle() {
        Handle handle = new Handle();
        handle.beanFactory = new RecordingBeanFactory(handle);
        assertThat(finalObservationProblems(handle)).isEmpty();
    }

    @Test
    void terminalRegistrationOfOrdinaryObjectIsRefusedAndNotRegistered() {
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        handle.terminal = true;
        Throwable thrown = catchThrowable(() -> beanFactory.registerSingleton("ordinarySynthetic", new Object()));
        assertThat(thrown).isInstanceOf(CreationViolation.class);
        assertThat(beanFactory.containsSingleton("ordinarySynthetic")).isFalse();
        assertThat(handle.violations).containsExactly("MANUAL_SINGLETON:AFTER_TERMINAL:ordinarySynthetic");
    }

    @Test
    void preTerminalProtectedNameRegistrationIsRefused() {
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        Throwable thrown = catchThrowable(() -> beanFactory.registerSingleton("dataSource", new Object()));
        assertThat(thrown).isInstanceOf(CreationViolation.class);
        assertThat(beanFactory.containsSingleton("dataSource")).isFalse();
        assertThat(handle.violations).containsExactly("MANUAL_SINGLETON:PROTECTED_NAME:dataSource");
    }

    @Test
    void preTerminalProtectedTypeRegistrationIsRefused() {
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        RejectedFactoryBean.reset();
        RejectedFactoryBean instance = new RejectedFactoryBean();
        Throwable thrown = catchThrowable(() -> beanFactory.registerSingleton("syntheticFactory", instance));
        assertThat(thrown).isInstanceOf(CreationViolation.class);
        assertThat(beanFactory.containsSingleton("syntheticFactory")).isFalse();
        assertThat(handle.violations).containsExactly("MANUAL_SINGLETON:PROTECTED_TYPE:syntheticFactory");
        assertThat(RejectedFactoryBean.GET_OBJECT.get()).isZero();
        assertThat(RejectedFactoryBean.GET_OBJECT_TYPE.get()).isZero();
        RejectedFactoryBean.reset();
    }

    @Test
    void preTerminalPolicySnapshotRegistrationIsPermitted() {
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        TestDatabasePolicyProvider.Resolved snapshot = TestDatabasePolicyProvider.resolve("ci", null);
        beanFactory.registerSingleton(GuardedTestDatabaseInitializer.POLICY_SNAPSHOT_NAME, snapshot);
        assertThat(beanFactory.containsSingleton(GuardedTestDatabaseInitializer.POLICY_SNAPSHOT_NAME)).isTrue();
        assertThat(handle.violations).isEmpty();
        assertThat(handle.manualSingletons).containsExactly(GuardedTestDatabaseInitializer.POLICY_SNAPSHOT_NAME);
    }

    // ---- SYNTHETIC Policy B mechanics: post-terminal ConditionEvaluationReport registration (no bootstrap) ----

    /** A real report instance obtained through Boot's own API on an unrelated plain factory (constructor is private). */
    private static ConditionEvaluationReport foreignReport() {
        return ConditionEvaluationReport.get(new DefaultListableBeanFactory());
    }

    /** ACTUAL get path: one permitted registration, cached singleton present, separate record, no violation. */
    @Test
    void postTerminalReportGetPathIsPermittedOnce() {
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        handle.terminal = true;
        ConditionEvaluationReport report = ConditionEvaluationReport.get(beanFactory);
        assertThat(beanFactory.containsSingleton(REPORT_BEAN_NAME)).isTrue();
        assertThat(beanFactory.getSingleton(REPORT_BEAN_NAME)).isSameAs(report);
        assertThat(handle.postTerminalReports).containsExactly(REPORT_BEAN_NAME + "|" + ConditionEvaluationReport.class.getName());
        assertThat(handle.violations).isEmpty();
        assertThat(handle.manualSingletons).isEmpty();
        // A repeated get finds the cached singleton and does NOT attempt a registration (not second-registration coverage).
        assertThat(ConditionEvaluationReport.get(beanFactory)).isSameAs(report);
        assertThat(handle.postTerminalReports).hasSize(1);
        assertThat(handle.violations).isEmpty();
    }

    /** PREDICATE: each condition individually false with all others satisfied. */
    @Test
    void postTerminalReportPredicateRequiresEveryCondition() {
        ConditionEvaluationReport report = foreignReport();
        Class<?> cer = ConditionEvaluationReport.class;
        assertThat(isPermittedPostTerminalReport(REPORT_BEAN_NAME, report, false, false, false, cer, "get")).isTrue();
        assertThat(isPermittedPostTerminalReport("otherReport", report, false, false, false, cer, "get")).as("name").isFalse();
        assertThat(isPermittedPostTerminalReport(REPORT_BEAN_NAME, new Object(), false, false, false, cer, "get"))
                .as("class").isFalse();
        assertThat(isPermittedPostTerminalReport(REPORT_BEAN_NAME, report, true, false, false, cer, "get")).as("parent")
                .isFalse();
        assertThat(isPermittedPostTerminalReport(REPORT_BEAN_NAME, report, false, true, false, cer, "get"))
                .as("existing singleton").isFalse();
        assertThat(isPermittedPostTerminalReport(REPORT_BEAN_NAME, report, false, false, true, cer, "get"))
                .as("already permitted").isFalse();
        assertThat(isPermittedPostTerminalReport(REPORT_BEAN_NAME, report, false, false, false,
                GuardedTestDatabaseBootDefinitionAttributionTest.class, "get")).as("caller class").isFalse();
        assertThat(isPermittedPostTerminalReport(REPORT_BEAN_NAME, report, false, false, false, cer, "find"))
                .as("caller method").isFalse();
        assertThat(isPermittedPostTerminalReport(REPORT_BEAN_NAME, report, false, false, false, null, null))
                .as("no caller").isFalse();
        assertThat(isPermittedPostTerminalReport("dataSource", report, false, false, false, cer, "get"))
                .as("protected name").isFalse();
        assertThat(isPermittedPostTerminalReport(REPORT_BEAN_NAME, new RejectedFactoryBean(), false, false, false, cer,
                "get")).as("protected type").isFalse();
        RejectedFactoryBean.reset();
    }

    /** INTERCEPTION: direct registrations (caller is this test) are refused, recorded, not registered, not counted. */
    @Test
    void postTerminalDirectRegistrationsAreRefused() {
        assertPostTerminalRefusal(REPORT_BEAN_NAME, new Object());
        assertPostTerminalRefusal("otherReport", foreignReport());
        assertPostTerminalRefusal(REPORT_BEAN_NAME, foreignReport());
        assertPostTerminalRefusal("dataSource", new Object());
        assertPostTerminalRefusal(REPORT_BEAN_NAME, new RejectedFactoryBean());
        RejectedFactoryBean.reset();
    }

    private static void assertPostTerminalRefusal(String name, Object object) {
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        handle.terminal = true;
        Throwable thrown = catchThrowable(() -> beanFactory.registerSingleton(name, object));
        assertThat(thrown).as(name).isInstanceOf(CreationViolation.class);
        assertThat(beanFactory.containsSingleton(name)).as(name).isFalse();
        assertThat(handle.violations).as(name).containsExactly("MANUAL_SINGLETON:AFTER_TERMINAL:" + name);
        assertThat(handle.postTerminalReports).as(name).isEmpty();
    }

    /** ACTUAL get path, genuine second registration attempt after the first permission (singleton destroyed first). */
    @Test
    void secondPostTerminalReportRegistrationThroughGetIsRefused() {
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        handle.terminal = true;
        ConditionEvaluationReport.get(beanFactory);
        beanFactory.destroySingleton(REPORT_BEAN_NAME);
        assertThat(beanFactory.containsSingleton(REPORT_BEAN_NAME)).isFalse();
        Throwable thrown = catchThrowable(() -> ConditionEvaluationReport.get(beanFactory));
        assertThat(thrown).isInstanceOf(CreationViolation.class);
        assertThat(beanFactory.containsSingleton(REPORT_BEAN_NAME)).isFalse();
        assertThat(handle.postTerminalReports).hasSize(1);
        assertThat(handle.violations).containsExactly("MANUAL_SINGLETON:AFTER_TERMINAL:" + REPORT_BEAN_NAME);
    }

    /** ACTUAL get path with a parent factory: refused before registration; no record. */
    @Test
    void postTerminalReportWithParentFactoryIsRefused() {
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        beanFactory.setParentBeanFactory(new DefaultListableBeanFactory());
        handle.terminal = true;
        Throwable thrown = catchThrowable(() -> ConditionEvaluationReport.get(beanFactory));
        assertThat(thrown).isInstanceOf(CreationViolation.class);
        assertThat(beanFactory.containsSingleton(REPORT_BEAN_NAME)).isFalse();
        assertThat(handle.postTerminalReports).isEmpty();
        assertThat(handle.violations).containsExactly("MANUAL_SINGLETON:AFTER_TERMINAL:" + REPORT_BEAN_NAME);
    }

    /** After a permitted report, terminal creation and DIRECT guard queries are still refused/recorded. */
    @Test
    void terminalCreationAndDirectQueriesRemainRefusedAfterPermittedReport() {
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        beanFactory.registerBeanDefinition("plain", new RootBeanDefinition(String.class));
        handle.terminal = true;
        ConditionEvaluationReport.get(beanFactory);
        Throwable created = catchThrowable(() -> beanFactory.check("CREATE", "plain", new RootBeanDefinition(String.class)));
        assertThat(created).isInstanceOf(CreationViolation.class);
        Throwable guarded = catchThrowable(() -> new TestDatabaseBeanDefinitionGuard(false).postProcessBeanFactory(beanFactory));
        assertThat(guarded).isInstanceOf(TestDatabaseGuardException.class);
        assertThat(handle.callsAfterStop).isEqualTo(1);
        assertThat(handle.violations).containsExactly("CREATE:AFTER_TERMINAL:plain", "DIRECT_AFTER_TERMINAL:plain");
        assertThat(handle.postTerminalReports).hasSize(1);
        assertThat(finalObservationProblems(handle)).contains("FINAL:VIOLATIONS", "FINAL:DIRECT_AFTER_TERMINAL");
    }

    /** Diagnostics render the separate record; a diagnostic failure is attached to, not replacing, the primary failure. */
    @Test
    void postTerminalReportDiagnosticsAndPrimaryFailurePreservation() {
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        handle.terminal = true;
        ConditionEvaluationReport.get(beanFactory);
        String p = DIAG_PREFIX + "POST_CLEANUP ";
        assertThat(diagnosticLines("POST_CLEANUP", handle)).containsSubsequence(p + "violations.size=0",
                p + "postTerminalReports.size=1",
                p + "postTerminalReports[0]=" + REPORT_BEAN_NAME + "|" + ConditionEvaluationReport.class.getName());
        AssertionError primary = new AssertionError("PRIMARY");
        cleanup(handle, null, primary, line -> {
            throw new IllegalStateException("SINK");
        });
        assertThat(primary.getMessage()).isEqualTo("PRIMARY");
        assertThat(primary.getSuppressed()).extracting(Throwable::getMessage).containsExactly("DIAG:POST_CLEANUP:FAILED");
    }

    // ---- SYNTHETIC AutoConfigurationPackages mechanics (Boot's real register() API; no bean creation) ----

    @Test
    void packagesRegisterShapeIsRecognized() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        AutoConfigurationPackages.register(beanFactory, "a.b");
        BeanDefinition definition = beanFactory.getBeanDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN);
        assertThat(definition.getClass()).isEqualTo(RootBeanDefinition.class);
        assertThat(((RootBeanDefinition) definition).getBeanClass().getName()).isEqualTo(BASE_PACKAGES_CLASS);
        assertThat(definition.getRole()).isEqualTo(BeanDefinition.ROLE_INFRASTRUCTURE);
        assertThat(definition.getConstructorArgumentValues().getIndexedArgumentValues()).hasSize(1);
        assertThat((String[]) definition.getConstructorArgumentValues().getIndexedArgumentValues().get(0).getValue())
                .containsExactly("a.b");
        assertThat(isBootAutoConfigurationPackagesDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN, definition, null)).isTrue();
        assertThat(beanFactory.getSingletonCount()).isZero();
    }

    @Test
    void packagesRepeatedRegistrationMergesAndIsRecognized() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        AutoConfigurationPackages.register(beanFactory, "a.b", "c.d");
        AutoConfigurationPackages.register(beanFactory, "c.d", "e.f");
        BeanDefinition definition = beanFactory.getBeanDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN);
        assertThat((String[]) definition.getConstructorArgumentValues().getIndexedArgumentValues().get(0).getValue())
                .containsExactly("a.b", "c.d", "e.f");
        assertThat(isBootAutoConfigurationPackagesDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN, definition, null)).isTrue();
        assertThat(beanFactory.getSingletonCount()).isZero();
    }

    /**
     * Calls the test factory's private check() directly with both path labels. The labels do not invoke the real
     * resolveBeforeInstantiation/createBeanInstance hooks, and no constructor argument resolution is executed.
     */
    @Test
    void packagesCheckAcceptsAtBothHooksWithoutCreation() {
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        AutoConfigurationPackages.register(beanFactory, "a.b");
        RootBeanDefinition merged = (RootBeanDefinition) beanFactory.getMergedBeanDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN);
        beanFactory.check("BEFORE_INSTANTIATION", AUTO_CONFIGURATION_PACKAGES_BEAN, merged);
        beanFactory.check("CREATE", AUTO_CONFIGURATION_PACKAGES_BEAN, merged);
        assertThat(handle.permittedPackages).containsExactly("BEFORE_INSTANTIATION:" + AUTO_CONFIGURATION_PACKAGES_BEAN,
                "CREATE:" + AUTO_CONFIGURATION_PACKAGES_BEAN);
        assertThat(handle.violations).isEmpty();
        assertThat(beanFactory.containsSingleton(AUTO_CONFIGURATION_PACKAGES_BEAN)).isFalse();
    }

    @Test
    void packagesWrongBfppClassAtReservedNameIsRefused() {
        assertPackagesRefused(definition -> definition.setBeanClass(Sentinel.class));
    }

    @Test
    void packagesProtectedClassAtReservedNameIsRefused() {
        assertPackagesRefused(definition -> definition.setBeanClass(RejectedFactoryBean.class));
    }

    @Test
    void packagesApplicationRoleIsRefused() {
        assertPackagesRefused(definition -> definition.setRole(BeanDefinition.ROLE_APPLICATION));
    }

    @Test
    void packagesAbstractDefinitionIsRefused() {
        assertPackagesRefused(definition -> definition.setAbstract(true));
    }

    @Test
    void packagesSupplierIsRefused() {
        assertPackagesRefused(definition -> definition.setInstanceSupplier(() -> {
            throw new AssertionError("SUPPLIER:INVOKED");
        }));
    }

    @Test
    void packagesFactoryMethodIsRefused() {
        assertPackagesRefused(definition -> definition.setFactoryMethodName("create"));
    }

    @Test
    void packagesFactoryBeanNameIsRefused() {
        assertPackagesRefused(definition -> definition.setFactoryBeanName("otherFactory"));
    }

    @Test
    void packagesExplicitTargetTypeIsRefused() {
        assertPackagesRefused(definition -> definition.setTargetType(ResolvableType.forClass(Object.class)));
    }

    @Test
    void packagesMethodOverrideIsRefused() {
        assertPackagesRefused(definition -> definition.getMethodOverrides().addOverride(new ReplaceOverride("get", "replacer")));
    }

    @Test
    void packagesInitMethodIsRefused() {
        assertPackagesRefused(definition -> definition.setInitMethodName("init"));
    }

    @Test
    void packagesDependsOnIsRefused() {
        assertPackagesRefused(definition -> definition.setDependsOn("other"));
    }

    @Test
    void packagesPropertyValueIsRefused() {
        assertPackagesRefused(definition -> definition.getPropertyValues().add("packages", "x"));
    }

    @Test
    void packagesSecondIndexedArgumentIsRefused() {
        assertPackagesRefused(definition -> definition.getConstructorArgumentValues().addIndexedArgumentValue(1, "x"));
    }

    @Test
    void packagesGenericArgumentIsRefused() {
        assertPackagesRefused(definition -> definition.getConstructorArgumentValues().addGenericArgumentValue("x"));
    }

    @Test
    void packagesNonStringArrayArgumentIsRefused() {
        assertPackagesRefused(definition -> definition.getConstructorArgumentValues().addIndexedArgumentValue(0, "a.b"));
    }

    @Test
    void packagesTypedArgumentHolderIsRefused() {
        assertPackagesRefused(definition -> definition.getConstructorArgumentValues().addIndexedArgumentValue(0,
                new ConstructorArgumentValues.ValueHolder(new String[] {"a.b"}, "java.lang.String[]")));
    }

    @Test
    void packagesNamedArgumentHolderIsRefused() {
        assertPackagesRefused(definition -> definition.getConstructorArgumentValues().addIndexedArgumentValue(0,
                new ConstructorArgumentValues.ValueHolder(new String[] {"a.b"}, null, "names")));
    }

    @Test
    void packagesMissingBeanClassIsRefused() {
        assertPackagesRefused(definition -> definition.setBeanClass(null));
    }

    /** The sole holder moves from index 0 to index 1; exactly one indexed argument remains. */
    @Test
    void packagesArgumentAtWrongIndexIsRefused() {
        assertPackagesRefused(definition -> {
            ConstructorArgumentValues arguments = definition.getConstructorArgumentValues();
            ConstructorArgumentValues.ValueHolder holder = arguments.getIndexedArgumentValues().get(0);
            arguments.clear();
            arguments.addIndexedArgumentValue(1, holder);
            assertThat(arguments.getIndexedArgumentValues()).containsOnlyKeys(1);
        });
    }

    /** Same bean class, role and constructor arguments copied into a parentless GenericBeanDefinition. */
    @Test
    void packagesNonRootDefinitionAtReservedNameIsRefused() {
        DefaultListableBeanFactory source = new DefaultListableBeanFactory();
        AutoConfigurationPackages.register(source, "a.b");
        GenericBeanDefinition copy = new GenericBeanDefinition(source.getBeanDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN));
        assertThat(copy.getParentName()).isNull();
        assertThat(copy.getBeanClass().getName()).isEqualTo(BASE_PACKAGES_CLASS);
        assertThat(copy.getRole()).isEqualTo(BeanDefinition.ROLE_INFRASTRUCTURE);
        assertThat(copy.getConstructorArgumentValues().getIndexedArgumentValues()).hasSize(1);
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        beanFactory.registerBeanDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN, copy);
        assertThat(isBootAutoConfigurationPackagesDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN, copy, null)).isFalse();
        RootBeanDefinition merged = (RootBeanDefinition) beanFactory.getMergedBeanDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN);
        assertReservedCheckRefused(handle, beanFactory, merged);
        assertThat(beanFactory.containsSingleton(AUTO_CONFIGURATION_PACKAGES_BEAN)).isFalse();
    }

    @Test
    void packagesConvertedArgumentHolderIsRefused() {
        assertPackagesRefused(definition -> definition.getConstructorArgumentValues().getIndexedArgumentValues().get(0)
                .setConvertedValue(new String[] {"a.b"}));
    }

    /** Correct definition under another name: not recognized; the general classifier still refuses BasePackages. */
    @Test
    void packagesCorrectClassUnderWrongNameIsRefused() {
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        AutoConfigurationPackages.register(beanFactory, "a.b");
        BeanDefinition definition = beanFactory.getBeanDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN);
        assertThat(isBootAutoConfigurationPackagesDefinition("otherName", definition, null)).isFalse();
        beanFactory.registerBeanDefinition("otherName", ((RootBeanDefinition) definition).cloneBeanDefinition());
        RootBeanDefinition merged = (RootBeanDefinition) beanFactory.getMergedBeanDefinition("otherName");
        Throwable thrown = catchThrowable(() -> beanFactory.check("CREATE", "otherName", merged));
        assertThat(thrown).isInstanceOf(CreationViolation.class);
        assertThat(handle.violations).containsExactly("CREATE:UNCLASSIFIABLE:otherName");
        assertThat(handle.permittedPackages).isEmpty();
    }

    /**
     * MULTI-CONDITION: RootBeanDefinition cannot carry a parent, so this also changes the definition class. The parent
     * is missing, so nothing is merged: check() receives a supplied RootBeanDefinition and reads the registered one.
     */
    @Test
    void packagesParentDefinitionIsNotRecognized() throws ClassNotFoundException {
        GenericBeanDefinition definition = new GenericBeanDefinition();
        definition.setBeanClass(Class.forName(BASE_PACKAGES_CLASS, false,
                GuardedTestDatabaseBootDefinitionAttributionTest.class.getClassLoader()));
        definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        definition.getConstructorArgumentValues().addIndexedArgumentValue(0, new String[] {"a.b"});
        definition.setParentName("parentDefinition");
        assertThat(isBootAutoConfigurationPackagesDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN, definition, null)).isFalse();
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        beanFactory.registerBeanDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN, definition);
        assertReservedCheckRefused(handle, beanFactory, new RootBeanDefinition());
        assertThat(beanFactory.containsSingleton(AUTO_CONFIGURATION_PACKAGES_BEAN)).isFalse();
    }

    /**
     * An ordinary Object registered through the unchanged registerSingleton override (it blocks protected names and
     * types only, not every wrong singleton); check() then refuses the reserved name and leaves the object in place.
     */
    @Test
    void packagesExistingSingletonOfDifferentClassIsNotRecognized() {
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        AutoConfigurationPackages.register(beanFactory, "a.b");
        BeanDefinition definition = beanFactory.getBeanDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN);
        Object foreign = new Object();
        beanFactory.registerSingleton(AUTO_CONFIGURATION_PACKAGES_BEAN, foreign);
        assertThat(isBootAutoConfigurationPackagesDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN, definition, foreign))
                .isFalse();
        RootBeanDefinition merged = (RootBeanDefinition) beanFactory.getMergedBeanDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN);
        assertReservedCheckRefused(handle, beanFactory, merged);
        assertThat(beanFactory.getSingleton(AUTO_CONFIGURATION_PACKAGES_BEAN)).isSameAs(foreign);
        assertThat(beanFactory.getSingletonCount()).isEqualTo(1);
        assertThat(handle.manualSingletons).containsExactly(AUTO_CONFIGURATION_PACKAGES_BEAN);
    }

    /** check() is called directly with the CREATE label; the real createBeanInstance hook is not invoked. */
    private static void assertReservedCheckRefused(Handle handle, RecordingBeanFactory beanFactory,
            RootBeanDefinition supplied) {
        Throwable thrown = catchThrowable(() -> beanFactory.check("CREATE", AUTO_CONFIGURATION_PACKAGES_BEAN, supplied));
        assertThat(thrown).isInstanceOf(CreationViolation.class);
        assertThat(handle.violations).containsExactly("CREATE:RESERVED_NAME_MISMATCH:" + AUTO_CONFIGURATION_PACKAGES_BEAN);
        assertThat(handle.permittedPackages).isEmpty();
    }

    /** Baseline from the real register() API, one condition altered, then helper and the test check() both refuse. */
    private static void assertPackagesRefused(Consumer<RootBeanDefinition> alteration) {
        Handle handle = new Handle();
        RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
        AutoConfigurationPackages.register(beanFactory, "a.b");
        RootBeanDefinition registered = (RootBeanDefinition) beanFactory.getBeanDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN);
        alteration.accept(registered);
        assertThat(isBootAutoConfigurationPackagesDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN, registered, null)).isFalse();
        RootBeanDefinition merged = (RootBeanDefinition) beanFactory.getMergedBeanDefinition(AUTO_CONFIGURATION_PACKAGES_BEAN);
        Throwable thrown = catchThrowable(() -> beanFactory.check("CREATE", AUTO_CONFIGURATION_PACKAGES_BEAN, merged));
        assertThat(thrown).isInstanceOf(CreationViolation.class);
        assertThat(handle.violations).containsExactly("CREATE:RESERVED_NAME_MISMATCH:" + AUTO_CONFIGURATION_PACKAGES_BEAN);
        assertThat(handle.permittedPackages).isEmpty();
        assertThat(beanFactory.containsSingleton(AUTO_CONFIGURATION_PACKAGES_BEAN)).isFalse();
    }

    private static <T extends Throwable> List<T> causes(Throwable thrown, Class<T> type) {
        List<T> found = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = thrown; current != null && seen.add(current); current = current.getCause()) {
            if (type.isInstance(current)) {
                found.add(type.cast(current));
            }
        }
        return found;
    }

    /**
     * Test-only, read-only recognition of Boot's AutoConfigurationPackages definition on the REGISTERED definition.
     * Shape is structural evidence only, not provenance. getTargetType() could expose a cached resolved type; Spring
     * caches it on MERGED clones, and Boot's register() sets none, so a registered definition is expected to return
     * null. That registered-vs-merged caching assumption is unverified (no test observes Spring's caching). Never calls
     * getBean/getType/isFactoryBean, suppliers, constructors or methods on the existing singleton.
     */
    static boolean isBootAutoConfigurationPackagesDefinition(String name, BeanDefinition definition,
            Object existingSingleton) {
        if (!AUTO_CONFIGURATION_PACKAGES_BEAN.equals(name) || definition == null
                || definition.getClass() != RootBeanDefinition.class) {
            return false;
        }
        RootBeanDefinition root = (RootBeanDefinition) definition;
        if (!root.hasBeanClass() || !BASE_PACKAGES_CLASS.equals(root.getBeanClass().getName())
                || root.getRole() != BeanDefinition.ROLE_INFRASTRUCTURE || root.isAbstract()
                || root.getParentName() != null || root.getInstanceSupplier() != null
                || root.getFactoryMethodName() != null || root.getFactoryBeanName() != null
                || root.getTargetType() != null || root.hasMethodOverrides() || root.getInitMethodNames() != null
                || (root.getDependsOn() != null && root.getDependsOn().length != 0)
                || !root.getPropertyValues().isEmpty()) {
            return false;
        }
        ConstructorArgumentValues arguments = root.getConstructorArgumentValues();
        if (!arguments.getGenericArgumentValues().isEmpty() || arguments.getIndexedArgumentValues().size() != 1) {
            return false;
        }
        ConstructorArgumentValues.ValueHolder holder = arguments.getIndexedArgumentValues().get(0);
        if (holder == null || !(holder.getValue() instanceof String[]) || holder.getType() != null
                || holder.getName() != null || holder.isConverted()) {
            return false;
        }
        return existingSingleton == null || existingSingleton.getClass() == root.getBeanClass();
    }

    static final String REPORT_BEAN_NAME = "autoConfigurationReport";

    /**
     * Policy B predicate (post-terminal only). Protected names/types take precedence. Every other condition is required:
     * exact name, exact ConditionEvaluationReport class, no parent factory, no existing singleton under the name, not yet
     * used by this Handle, and immediate caller exactly ConditionEvaluationReport.get (class identity + method name).
     * Reads supplied values only; no bean, type, factory or supplier access.
     */
    static boolean isPermittedPostTerminalReport(String beanName, Object singletonObject, boolean parentPresent,
            boolean singletonPresent, boolean alreadyPermitted, Class<?> callerClass, String callerMethod) {
        if (singletonObject == null || PROTECTED_NAMES.contains(beanName)) {
            return false;
        }
        for (Class<?> guarded : PROTECTED_TYPES) {
            if (guarded.isInstance(singletonObject)) {
                return false;
            }
        }
        return REPORT_BEAN_NAME.equals(beanName) && singletonObject.getClass() == ConditionEvaluationReport.class
                && !parentPresent && !singletonPresent && !alreadyPermitted
                && callerClass == ConditionEvaluationReport.class && "get".equals(callerMethod);
    }

    /** Non-recursive: only the bean name and the supplied definition are inspected. */
    static String classify(String beanName, RootBeanDefinition definition) {
        if (PROTECTED_NAMES.contains(beanName)) {
            return "PROTECTED_NAME";
        }
        Class<?> type;
        if (definition.getFactoryMethodName() != null) {
            Method factoryMethod = definition.getResolvedFactoryMethod();
            if (factoryMethod == null) {
                return "UNCLASSIFIABLE";
            }
            type = factoryMethod.getReturnType();
        }
        else if (definition.hasBeanClass()) {
            type = definition.getBeanClass();
        }
        else {
            return "UNCLASSIFIABLE";
        }
        for (Class<?> guarded : PROTECTED_TYPES) {
            if (guarded.isAssignableFrom(type)) {
                return "PROTECTED_TYPE";
            }
        }
        return BeanFactoryPostProcessor.class.isAssignableFrom(type) ? "PERMITTED" : "UNCLASSIFIABLE";
    }

    /** Per-control state; created by the test before loading, filled by the loader, factory, probe and sentinel. */
    static final class Handle {
        int created;
        WebApplicationType webType;
        RecordingBeanFactory beanFactory;
        ConfigurableApplicationContext context;
        final List<String> violations = new ArrayList<>();
        final List<String> manualSingletons = new ArrayList<>();
        final List<String> permittedMetadata = new ArrayList<>();
        final List<String> permittedPackages = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final List<String> returnedTrue = new ArrayList<>();
        final List<String> traceErrors = new ArrayList<>();
        final List<String> diagnosticFailures = new ArrayList<>();
        // Successful Policy B post-terminal report registrations ("name|class"), recorded only after registration.
        final List<String> postTerminalReports = new ArrayList<>();
        String controlId;
        List<String> firstDirectSingletons;
        boolean firstDirectCaptureFailed;
        // BOOT21 diagnostic: installed BPP list at the first instrumented DIRECT isFactoryBean query, before delegation.
        List<String> firstDirectBpps;
        boolean firstDirectBppCaptureFailed;
        boolean firstDirectLtwDefinition;
        boolean firstDirectLtwSingleton;
        List<String> snapshot;
        Boolean lastResult;
        int nestedCalls;
        int callsAfterStop;
        boolean terminal;
        boolean sentinelReached;
        boolean foreignFactory;
        boolean probeRan;
        boolean fixtureDefined;
        boolean emfPresent;
        String emfDeclaringClass;
        String emfMethod;
        String emfReturnType;
        boolean emfStatic;
        String emfConfigurationClass;
        String emfFactoryType;
        boolean emfRecognized;
    }

    /** Explicit loader: only the application-context factory is replaced. */
    static final class RecordingBootContextLoader extends SpringBootContextLoader {

        @Override
        protected ApplicationContextFactory getApplicationContextFactory(MergedContextConfiguration mergedConfig) {
            return webApplicationType -> {
                Handle handle = HANDLE.get();
                if (handle == null) {
                    throw new IllegalStateException("HANDLE:MISSING");
                }
                if (handle.context != null) {
                    throw new IllegalStateException("HANDLE:REUSED");
                }
                RecordingBeanFactory beanFactory = new RecordingBeanFactory(handle);
                AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(beanFactory);
                handle.webType = webApplicationType;
                handle.beanFactory = beanFactory;
                handle.context = context;
                handle.created++;
                return context;
            };
        }
    }

    /** Records DIRECT guard isFactoryBean calls and refuses protected or unclassifiable bean creation. */
    static final class RecordingBeanFactory extends DefaultListableBeanFactory {

        final Handle handle;

        RecordingBeanFactory(Handle handle) {
            this.handle = handle;
        }

        @Override
        public boolean isFactoryBean(String name) {
            // Immediate caller of this override; no helper frame in between.
            StackWalker.StackFrame caller = WALKER.walk(frames -> frames.skip(1).findFirst()).orElse(null);
            boolean direct = caller != null && caller.getDeclaringClass() == TestDatabaseBeanDefinitionGuard.class
                    && GUARD_METHOD.equals(caller.getMethodName());
            if (!direct) {
                handle.nestedCalls++;
                return super.isFactoryBean(name);
            }
            if (handle.terminal) {
                handle.callsAfterStop++;
                handle.violations.add("DIRECT_AFTER_TERMINAL:" + name);
            }
            if (handle.snapshot == null) {
                List<String> names = new ArrayList<>();
                for (String definitionName : getBeanDefinitionNames()) {
                    if (!getMergedBeanDefinition(definitionName).isAbstract()) {
                        names.add(definitionName);
                    }
                }
                handle.snapshot = List.copyOf(names);
                captureFirstDirectSingletons();
                captureFirstDirectBpps();
            }
            handle.events.add("ENTRY:" + name);
            try {
                boolean result = super.isFactoryBean(name);
                handle.events.add("RETURN:" + name + ":" + result);
                handle.lastResult = result;
                if (result) {
                    handle.returnedTrue.add(name);
                }
                return result;
            } catch (RuntimeException | Error failure) {
                handle.events.add("THROW:" + name + ":" + failure.getClass().getName());
                handle.traceErrors.add("THROW:" + name);
                throw failure;
            }
        }

        /**
         * Reads the installed BeanPostProcessor list once (class identity and instanceof only; no callbacks, no type
         * queries, no bean access) plus local loadTimeWeaver definition/singleton presence.
         */
        private void captureFirstDirectBpps() {
            try {
                List<String> entries = new ArrayList<>();
                for (org.springframework.beans.factory.config.BeanPostProcessor processor : getBeanPostProcessors()) {
                    entries.add(processor.getClass().getName() + "|smart=" + (processor
                            instanceof org.springframework.beans.factory.config.SmartInstantiationAwareBeanPostProcessor));
                }
                handle.firstDirectLtwDefinition = containsBeanDefinition("loadTimeWeaver");
                handle.firstDirectLtwSingleton = containsSingleton("loadTimeWeaver");
                handle.firstDirectBpps = List.copyOf(entries);
            } catch (RuntimeException failure) {
                handle.firstDirectBppCaptureFailed = true;
                handle.diagnosticFailures.add("DIAG:FIRST_DIRECT_BPPS:FAILED");
            }
        }

        /** Cache lookups without early references: singleton names, runtime classes and local-definition presence. */
        private void captureFirstDirectSingletons() {
            recordFirstDirectSingletons(handle, this::getSingletonNames, name -> getSingleton(name, false),
                    this::containsBeanDefinition);
        }

        @Override
        protected Object resolveBeforeInstantiation(String beanName, RootBeanDefinition mbd) {
            check("BEFORE_INSTANTIATION", beanName, mbd);
            return super.resolveBeforeInstantiation(beanName, mbd);
        }

        @Override
        protected BeanWrapper createBeanInstance(String beanName, RootBeanDefinition mbd, Object[] args) {
            check("CREATE", beanName, mbd);
            return super.createBeanInstance(beanName, mbd, args);
        }

        /**
         * Protected names or types are refused before delegation and never registered; others delegate unchanged, so
         * this does not block every wrong singleton (e.g. a plain Object under the AutoConfigurationPackages name).
         */
        @Override
        public void registerSingleton(String beanName, Object singletonObject) {
            // Immediate caller of this override, derived at entry (frame 0 is this method; no helper frame in between).
            StackWalker.StackFrame caller = WALKER.walk(frames -> frames.skip(1).findFirst()).orElse(null);
            if (handle.terminal) {
                // Policy B: the single post-terminal exception is Boot's failure-reporting ConditionEvaluationReport.get
                // call shape. It identifies the immediate call shape only, not upstream provenance.
                if (!isPermittedPostTerminalReport(beanName, singletonObject, getParentBeanFactory() != null,
                        containsSingleton(beanName), !handle.postTerminalReports.isEmpty(),
                        caller == null ? null : caller.getDeclaringClass(),
                        caller == null ? null : caller.getMethodName())) {
                    handle.violations.add("MANUAL_SINGLETON:AFTER_TERMINAL:" + beanName);
                    throw new CreationViolation();
                }
                super.registerSingleton(beanName, singletonObject);
                // Committed only after the real registration returned; a failure above propagates unrecorded.
                handle.postTerminalReports.add(beanName + "|" + singletonObject.getClass().getName());
                return;
            }
            if (PROTECTED_NAMES.contains(beanName)) {
                handle.violations.add("MANUAL_SINGLETON:PROTECTED_NAME:" + beanName);
                throw new CreationViolation();
            }
            for (Class<?> guarded : PROTECTED_TYPES) {
                if (guarded.isInstance(singletonObject)) {
                    handle.violations.add("MANUAL_SINGLETON:PROTECTED_TYPE:" + beanName);
                    throw new CreationViolation();
                }
            }
            handle.manualSingletons.add(beanName);
            super.registerSingleton(beanName, singletonObject);
        }

        private void check(String path, String beanName, RootBeanDefinition mbd) {
            if (handle.terminal) {
                handle.violations.add(path + ":AFTER_TERMINAL:" + beanName);
                throw new CreationViolation();
            }
            // Same shared contract as the guard, evaluated on the registered definition before the broad rules.
            BeanDefinition registered = containsBeanDefinition(beanName) ? getBeanDefinition(beanName) : null;
            if (TestDatabaseBeanDefinitionGuard.isBootMetadataFactoryDefinition(beanName, registered,
                    getSingleton(beanName, false))) {
                handle.permittedMetadata.add(path + ":" + beanName);
                return;
            }
            // Reserved name: only the complete AutoConfigurationPackages contract is accepted; no general fallback.
            if (AUTO_CONFIGURATION_PACKAGES_BEAN.equals(beanName)) {
                if (isBootAutoConfigurationPackagesDefinition(beanName, registered, getSingleton(beanName, false))) {
                    handle.permittedPackages.add(path + ":" + beanName);
                    return;
                }
                handle.violations.add(path + ":RESERVED_NAME_MISMATCH:" + beanName);
                throw new CreationViolation();
            }
            String verdict = classify(beanName, mbd);
            if (!"PERMITTED".equals(verdict)) {
                handle.violations.add(path + ":" + verdict + ":" + beanName);
                throw new CreationViolation();
            }
        }
    }

    /** Runs before the context-supplied guard; definition-only reads, no bean creation. */
    static class Probe implements BeanDefinitionRegistryPostProcessor, Ordered {

        @Override
        public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            if (!(beanFactory instanceof RecordingBeanFactory recording)) {
                Handle handle = HANDLE.get();
                if (handle != null) {
                    handle.foreignFactory = true;
                }
                throw new IllegalStateException("PROBE:FOREIGN_FACTORY");
            }
            Handle handle = recording.handle;
            handle.probeRan = true;
            handle.fixtureDefined = beanFactory.containsBeanDefinition(FIXTURE);
            handle.emfPresent = beanFactory.containsBeanDefinition(EMF);
            if (!handle.emfPresent) {
                return;
            }
            BeanDefinition definition = beanFactory.getBeanDefinition(EMF);
            if (definition instanceof AnnotatedBeanDefinition annotated
                    && annotated.getFactoryMethodMetadata() != null) {
                MethodMetadata method = annotated.getFactoryMethodMetadata();
                handle.emfDeclaringClass = method.getDeclaringClassName();
                handle.emfMethod = method.getMethodName();
                handle.emfReturnType = method.getReturnTypeName();
                handle.emfStatic = method.isStatic();
            }
            String configuration = definition.getFactoryBeanName();
            handle.emfConfigurationClass = configuration != null && beanFactory.containsBeanDefinition(configuration)
                    ? beanFactory.getBeanDefinition(configuration).getBeanClassName() : null;
            Class<?> factoryType = beanFactory.getType(BeanFactory.FACTORY_BEAN_PREFIX + EMF, false);
            handle.emfFactoryType = factoryType == null ? null : factoryType.getName();
            handle.emfRecognized = TestDatabaseBeanDefinitionGuard.isBootJpaFactoryDefinition(beanFactory, EMF);
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }

    /** Bean-defined BFPP: runs after the context-supplied guard returned normally, then stops the refresh. */
    static class Sentinel implements BeanFactoryPostProcessor, PriorityOrdered {

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            if (!(beanFactory instanceof RecordingBeanFactory recording)) {
                Handle handle = HANDLE.get();
                if (handle != null) {
                    handle.foreignFactory = true;
                }
                throw new IllegalStateException("SENTINEL:FOREIGN_FACTORY");
            }
            recording.handle.sentinelReached = true;
            recording.handle.terminal = true;
            throw new SentinelStop();
        }

        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE;
        }
    }

    static final class SentinelStop extends RuntimeException {
        private static final long serialVersionUID = 1L;

        SentinelStop() {
            super("SENTINEL:STOP", null, false, false);
        }
    }

    static final class CreationViolation extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CreationViolation() {
            super("CREATION:REFUSED", null, false, false);
        }
    }

    /** Deliberately unsupported definition; every entry point counts. */
    static class RejectedFactoryBean implements FactoryBean<String> {

        static final AtomicInteger CONSTRUCTED = new AtomicInteger();
        static final AtomicInteger GET_OBJECT = new AtomicInteger();
        static final AtomicInteger GET_OBJECT_TYPE = new AtomicInteger();

        RejectedFactoryBean() {
            CONSTRUCTED.incrementAndGet();
        }

        static void reset() {
            CONSTRUCTED.set(0);
            GET_OBJECT.set(0);
            GET_OBJECT_TYPE.set(0);
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

    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
            "TEST_DB_MODE=ci",
            "spring.datasource.url=jdbc:postgresql://localhost:5432/task_manager_test",
            "spring.datasource.username=jtm_test"})
    @GuardedTestDatabase
    @ContextConfiguration(loader = RecordingBootContextLoader.class,
            initializers = GuardedTestDatabaseInitializer.class)
    @Import({Probe.class, Sentinel.class})
    static class SbCompatFixture {
    }

    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
            "TEST_DB_MODE=ci",
            "spring.datasource.url=jdbc:postgresql://localhost:5432/task_manager_test",
            "spring.datasource.username=jtm_test"})
    @GuardedTestDatabase
    @ContextConfiguration(loader = RecordingBootContextLoader.class,
            initializers = GuardedTestDatabaseInitializer.class)
    @Import({Probe.class, Sentinel.class, RejectedFactoryBean.class})
    static class SbAttribFixture {
    }

    @DataJpaTest(properties = {
            "TEST_DB_MODE=ci",
            "spring.datasource.url=jdbc:postgresql://localhost:5432/task_manager_test",
            "spring.datasource.username=jtm_test"})
    @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
    @GuardedTestDatabase
    @ContextConfiguration(loader = RecordingBootContextLoader.class,
            initializers = GuardedTestDatabaseInitializer.class)
    @Import({Probe.class, Sentinel.class})
    static class JpaCompatFixture {
    }

    @DataJpaTest(properties = {
            "TEST_DB_MODE=ci",
            "spring.datasource.url=jdbc:postgresql://localhost:5432/task_manager_test",
            "spring.datasource.username=jtm_test"})
    @AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
    @GuardedTestDatabase
    @ContextConfiguration(loader = RecordingBootContextLoader.class,
            initializers = GuardedTestDatabaseInitializer.class)
    @Import({Probe.class, Sentinel.class, RejectedFactoryBean.class})
    static class JpaAttribFixture {
    }
}
