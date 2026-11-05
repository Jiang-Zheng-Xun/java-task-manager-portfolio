package io.github.jiangzhengxun.taskmanager.support.testdb;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationInitializer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Positive guarded Spring bootstrap with the real strategy, verifier, Flyway and JPA. {@link Control} is evaluated as a
 * class-level condition before the Spring context is loaded: property {@code taskmanager.guardedMigrationJpa.control}
 * absent disables the class; exactly {@code MIGRATE_JPA} enables it; any other value, including empty, fails the class
 * without starting a context, reading credentials or connecting.
 *
 * <p>Future execution requires separate authorization, local mode with the existing private policy and separately
 * supplied datasource settings. It can write the Flyway history and application schema through the existing migration
 * only. Database access: guarded identity query, Flyway migration, JPA bootstrap, and one {@link Flyway#info()} call in
 * the test method. It is not a single-connection control.
 *
 * <p>Ordering is observed on the strategy's context-local timeline: identity verified and released, migrate invoked,
 * migrate returned (with migrationsExecuted), Flyway initializer done, EMF FactoryBean initialized, EMF product available.
 * This orders the observed events on the recording thread only; it does not inspect database sessions or pool state.
 */
@ExtendWith(GuardedTestDatabaseMigrationJpaIntegrationTest.Control.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@GuardedTestDatabase
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class GuardedTestDatabaseMigrationJpaIntegrationTest {

    static final String CONTROL = "taskmanager.guardedMigrationJpa.control";

    @Autowired
    private Flyway flyway;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TimelineObserver observer;

    /** Static @Bean: the observer needs no configuration instance and declares no dependencies. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TimelineObserverConfiguration {

        @Bean
        static TimelineObserver guardedMigrationTimelineObserver() {
            return new TimelineObserver();
        }
    }

    /**
     * Observation-only BeanPostProcessor (not Smart, not InstantiationAware): no dependencies, no getBean, no type
     * queries. It adopts the strategy's context-local timeline when the strategy bean is initialized and appends fixed
     * labels for objects Spring passes to it. Its registration is itself a context change; impact is not claimed zero.
     */
    static final class TimelineObserver implements BeanPostProcessor {
        static final String FLYWAY_INITIALIZER_DONE = "FLYWAY_INITIALIZER_DONE";
        static final String EMF_FACTORYBEAN_INITIALIZED = "EMF_FACTORYBEAN_INITIALIZED";
        static final String EMF_PRODUCT_AVAILABLE = "EMF_PRODUCT_AVAILABLE";

        private GuardedFlywayMigrationStrategy.Timeline timeline;
        private int unobserved;

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof GuardedFlywayMigrationStrategy strategy) {
                if (timeline == null) {
                    timeline = strategy.timeline();
                } else {
                    unobserved++;
                }
            } else if ("flywayInitializer".equals(beanName) && bean instanceof FlywayMigrationInitializer) {
                record(FLYWAY_INITIALIZER_DONE);
            } else if ("entityManagerFactory".equals(beanName)) {
                if (bean instanceof LocalContainerEntityManagerFactoryBean) {
                    record(EMF_FACTORYBEAN_INITIALIZED);
                } else if (bean instanceof EntityManagerFactory) {
                    record(EMF_PRODUCT_AVAILABLE);
                }
            }
            return bean;
        }

        private synchronized void record(String kind) {
            if (timeline == null) {
                unobserved++;
                return;
            }
            timeline.record(kind, -1);
        }

        synchronized GuardedFlywayMigrationStrategy.Timeline timeline() {
            return timeline;
        }

        synchronized int unobserved() {
            return unobserved;
        }
    }

    @Test
    void controlMigrateJpa_guardedStartupLeavesAppliedSchemaAndUsableJpa() {
        List<String> problems = new ArrayList<>();
        Throwable primary = null;
        EntityManager entityManager = null;
        try {
            MigrationInfoService info = flyway.info();
            MigrationInfo current = info.current();
            assertThat(current).as("applied schema version").isNotNull();
            assertThat(current.getState().isApplied()).as("current applied").isTrue();
            assertThat(current.getState().isFailed()).as("current failed").isFalse();
            assertThat(info.pending()).as("pending migrations").isEmpty();

            // Six-kind ordering on the strategy's context-local timeline (observation only; fixed labels).
            GuardedFlywayMigrationStrategy.Timeline timeline = observer.timeline();
            assertThat(timeline).as("strategy timeline observed").isNotNull();
            assertThat(observer.unobserved()).as("events without timeline or duplicate strategy").isZero();
            List<GuardedFlywayMigrationStrategy.Timeline.Event> events = timeline.events();
            for (GuardedFlywayMigrationStrategy.Timeline.Event event : events) {
                System.out.println("BOOT28_TIMELINE seq=" + event.sequence() + " kind=" + event.kind() + " count="
                        + event.count() + " thread=" + event.thread());
            }
            System.out.println("BOOT28_MIGRATION pendingAfter=" + info.pending().length + " currentApplied="
                    + current.getState().isApplied() + " currentFailed=" + current.getState().isFailed());
            assertThat(events).extracting(GuardedFlywayMigrationStrategy.Timeline.Event::kind).containsExactly(
                    GuardedFlywayMigrationStrategy.Timeline.IDENTITY_VERIFIED_AND_RELEASED,
                    GuardedFlywayMigrationStrategy.Timeline.MIGRATE_INVOKED,
                    GuardedFlywayMigrationStrategy.Timeline.MIGRATE_RETURNED,
                    TimelineObserver.FLYWAY_INITIALIZER_DONE,
                    TimelineObserver.EMF_FACTORYBEAN_INITIALIZED,
                    TimelineObserver.EMF_PRODUCT_AVAILABLE);
            for (int i = 1; i < events.size(); i++) {
                assertThat(events.get(i).sequence()).as("strictly increasing sequence")
                        .isGreaterThan(events.get(i - 1).sequence());
            }
            assertThat(events.get(2).count()).as("migrationsExecuted recorded").isGreaterThanOrEqualTo(0);
            assertThat(events).extracting(GuardedFlywayMigrationStrategy.Timeline.Event::thread)
                    .as("single recording thread").containsOnly(events.get(0).thread());

            assertThat(entityManagerFactory.isOpen()).as("entity manager factory open").isTrue();
            entityManager = entityManagerFactory.createEntityManager();
            assertThat(entityManager.getMetamodel().getEntities()).as("mapped entities").isNotEmpty();
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            if (entityManager != null) {
                try {
                    entityManager.close();
                } catch (Throwable closeFailure) {
                    problems.add("CLEANUP:ENTITY_MANAGER_CLOSE_FAILED");
                }
            }
            if (!problems.isEmpty()) {
                if (primary != null) {
                    for (String label : problems) {
                        primary.addSuppressed(new AssertionError(label));
                    }
                } else {
                    AssertionError failure = new AssertionError("CLEANUP:FAILED");
                    problems.forEach(label -> failure.addSuppressed(new AssertionError(label)));
                    throw failure;
                }
            }
        }
    }

    /** Exact control selection, evaluated before any Spring context is loaded. */
    static final class Control implements ExecutionCondition {

        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            String control = System.getProperty(CONTROL);
            if (control == null) {
                return ConditionEvaluationResult.disabled("CONTROL:ABSENT");
            }
            if (!"MIGRATE_JPA".equals(control)) {
                throw new IllegalStateException("CONTROL:UNKNOWN");
            }
            return ConditionEvaluationResult.enabled("CONTROL:MIGRATE_JPA");
        }
    }
}
