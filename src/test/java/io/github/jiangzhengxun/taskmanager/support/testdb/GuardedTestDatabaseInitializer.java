package io.github.jiangzhengxun.taskmanager.support.testdb;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * No-argument entry point used by {@link GuardedTestDatabase}. Resolves the independent policy once, supplies the fixed
 * Hikari and pgJDBC timeouts as a property source, delegates every check to
 * {@link TestDatabaseGuardInitializer#guarded(TestDatabaseGuardPolicy)} before refresh, then keeps the resolved policy
 * as this context's immutable snapshot.
 *
 * <p>The snapshot is a manually registered singleton of this context's own bean factory under
 * {@link #POLICY_SNAPSHOT_NAME}. It is neither a FactoryBean nor a guarded type, so the bean definition guard applies
 * unchanged. {@link GuardedTestDatabaseConfiguration} reads it through {@link #policySnapshot}; the mode and policy file
 * are never resolved again, nothing is cached statically and no other context's snapshot is visible.
 */
public final class GuardedTestDatabaseInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    static final String TIMEOUT_SOURCE_NAME = "testDatabaseGuardDriverTimeouts";
    static final String POLICY_SNAPSHOT_NAME = "testDatabaseGuardPolicySnapshot";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment environment = context.getEnvironment();
        TestDatabasePolicyProvider.Resolved resolved = TestDatabasePolicyProvider.resolve(environment);
        ConfigurableListableBeanFactory beanFactory = beanFactory(context);
        // A second registration is rejected before any setting is supplied.
        requireNoPolicySnapshot(beanFactory);
        // Highest precedence; a conflicting value in any other source is still rejected by the delegate.
        environment.getPropertySources().addFirst(
                new MapPropertySource(TIMEOUT_SOURCE_NAME, TestDatabaseGuardInitializer.DRIVER_TIMEOUTS));
        TestDatabaseGuardInitializer.guarded(resolved.guardPolicy()).initialize(context);
        registerPolicySnapshot(beanFactory, resolved);
    }

    /** Registers the snapshot once; an existing singleton or definition under the snapshot name is rejected. */
    static void registerPolicySnapshot(ConfigurableListableBeanFactory beanFactory,
            TestDatabasePolicyProvider.Resolved snapshot) {
        requireNoPolicySnapshot(beanFactory);
        try {
            beanFactory.registerSingleton(POLICY_SNAPSHOT_NAME, snapshot);
        } catch (RuntimeException alreadyRegistered) {
            throw reject(Reason.POLICY_SNAPSHOT_DUPLICATE);
        }
    }

    /**
     * Returns this bean factory's snapshot. Exactly one snapshot must exist, as the manual singleton registered under
     * {@link #POLICY_SNAPSHOT_NAME}; parent factories are not consulted.
     */
    static TestDatabasePolicyProvider.Resolved policySnapshot(ConfigurableListableBeanFactory beanFactory) {
        try {
            // Local lookups only; allowEagerInit=false predicts types without creating beans.
            String[] names = beanFactory.getBeanNamesForType(TestDatabasePolicyProvider.Resolved.class, true, false);
            boolean definition = beanFactory.containsBeanDefinition(POLICY_SNAPSHOT_NAME);
            Object snapshot = beanFactory.getSingleton(POLICY_SNAPSHOT_NAME);
            if (names.length == 0 && !definition && snapshot == null) {
                throw reject(Reason.POLICY_SNAPSHOT_MISSING);
            }
            if (names.length != 1 || !POLICY_SNAPSHOT_NAME.equals(names[0]) || definition) {
                throw reject(Reason.POLICY_SNAPSHOT_DUPLICATE);
            }
            if (!(snapshot instanceof TestDatabasePolicyProvider.Resolved resolved)) {
                throw reject(Reason.POLICY_SNAPSHOT_DUPLICATE);
            }
            return resolved;
        } catch (TestDatabaseGuardException rejection) {
            throw reject(rejection.reason());
        } catch (RuntimeException inspectionFailure) {
            // External messages may name beans or types; they are replaced by a fixed diagnostic.
            throw reject(Reason.POLICY_SNAPSHOT_MISSING);
        }
    }

    private static ConfigurableListableBeanFactory beanFactory(ConfigurableApplicationContext context) {
        try {
            return context.getBeanFactory();
        } catch (RuntimeException unavailable) {
            // Without this context's own registry the snapshot cannot be kept.
            throw reject(Reason.POLICY_SNAPSHOT_MISSING);
        }
    }

    private static void requireNoPolicySnapshot(ConfigurableListableBeanFactory beanFactory) {
        if (beanFactory.containsSingleton(POLICY_SNAPSHOT_NAME)
                || beanFactory.containsBeanDefinition(POLICY_SNAPSHOT_NAME)) {
            throw reject(Reason.POLICY_SNAPSHOT_DUPLICATE);
        }
    }

    private static TestDatabaseGuardException reject(Reason reason) {
        return new TestDatabaseGuardException(Stage.POLICY, reason);
    }
}
