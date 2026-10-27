package io.github.jiangzhengxun.taskmanager.support.testdb;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
import org.springframework.boot.context.properties.source.IterableConfigurationPropertySource;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Checks the explicit mode and resolved datasource settings against an independent policy before refresh, then
 * registers {@link TestDatabaseBeanDefinitionGuard} as an ordinary bean factory post-processor.
 *
 * <p>Only {@code spring.flyway.enabled} is allowed under {@code spring.flyway}, and its effective value must be
 * {@code true} (absence uses Boot's default {@code true}). Names are inspected in Boot's mapped canonical form across
 * every property source, regardless of precedence. External runtime exceptions are replaced by fixed diagnostics.
 *
 * <p>Arbitrary initializers, registry post-processors, static initializers and other early custom code are outside
 * this guard's guarantee and require source review.
 */
public final class TestDatabaseGuardInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String MODE_PROPERTY = "TEST_DB_MODE";

    private static final ConfigurationPropertyName DATASOURCE = ConfigurationPropertyName.of("spring.datasource");
    private static final ConfigurationPropertyName FLYWAY = ConfigurationPropertyName.of("spring.flyway");
    private static final ConfigurationPropertyName HIKARI = ConfigurationPropertyName.of("spring.datasource.hikari");
    private static final ConfigurationPropertyName FLYWAY_ENABLED = ConfigurationPropertyName.of("spring.flyway.enabled");

    private static final Set<ConfigurationPropertyName> ALLOWED_DATASOURCE = Set.of(
            ConfigurationPropertyName.of("spring.datasource.url"),
            ConfigurationPropertyName.of("spring.datasource.username"),
            ConfigurationPropertyName.of("spring.datasource.password"));

    // Pool sizing and timing only; any other Hikari key (jdbc-url, credentials, data-source-*) is rejected.
    private static final Set<ConfigurationPropertyName> ALLOWED_HIKARI = Set.of(
            ConfigurationPropertyName.of("spring.datasource.hikari.pool-name"),
            ConfigurationPropertyName.of("spring.datasource.hikari.maximum-pool-size"),
            ConfigurationPropertyName.of("spring.datasource.hikari.minimum-idle"),
            ConfigurationPropertyName.of("spring.datasource.hikari.connection-timeout"),
            ConfigurationPropertyName.of("spring.datasource.hikari.idle-timeout"),
            ConfigurationPropertyName.of("spring.datasource.hikari.max-lifetime"),
            ConfigurationPropertyName.of("spring.datasource.hikari.validation-timeout"),
            ConfigurationPropertyName.of("spring.datasource.hikari.keepalive-time"));

    private final TestDatabaseGuardPolicy policy;

    /** A null policy is rejected during initialization as {@code POLICY_MISSING}. */
    public TestDatabaseGuardInitializer(TestDatabaseGuardPolicy policy) {
        this.policy = policy;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment environment = context.getEnvironment();
        TestDatabaseConfigValidator.Snapshot snapshot = resolveSnapshot(environment);
        TestDatabaseConfigValidator.Result result =
                TestDatabaseConfigValidator.validate(snapshot, policy == null ? null : policy.targets());
        if (!result.isSuccess()) {
            throw reject(Reason.valueOf(result.error().orElseThrow().name()));
        }
        rejectAlternateSettings(environment);
        requireFlywayEnabled(environment);
        context.addBeanFactoryPostProcessor(new TestDatabaseBeanDefinitionGuard());
    }

    private static TestDatabaseConfigValidator.Snapshot resolveSnapshot(ConfigurableEnvironment environment) {
        try {
            Binder binder = Binder.get(environment);
            return new TestDatabaseConfigValidator.Snapshot(
                    environment.getProperty(MODE_PROPERTY),
                    binder.bind("spring.datasource.url", String.class).orElse(null),
                    binder.bind("spring.datasource.username", String.class).orElse(null));
        } catch (RuntimeException unresolvable) {
            // Resolution exceptions may describe property values; they are replaced by a fixed diagnostic.
            throw reject(Reason.SETTINGS_UNRESOLVABLE);
        }
    }

    private static void rejectAlternateSettings(ConfigurableEnvironment environment) {
        try {
            for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
                if (source instanceof IterableConfigurationPropertySource iterable) {
                    try (Stream<ConfigurationPropertyName> names = iterable.stream()) {
                        if (names.anyMatch(TestDatabaseGuardInitializer::isRejectedName)) {
                            throw reject(Reason.ALTERNATE_SETTING_REJECTED);
                        }
                    }
                } else if (mayContain(source, DATASOURCE) || mayContain(source, FLYWAY)) {
                    throw reject(Reason.PROPERTY_SOURCE_UNVERIFIABLE);
                }
            }
        } catch (TestDatabaseGuardException rejection) {
            // A fresh exception keeps the fixed reason and drops any suppressed close failure.
            throw reject(rejection.reason());
        } catch (RuntimeException inspectionFailure) {
            // Enumeration or query exceptions may describe property names or values.
            throw reject(Reason.PROPERTY_SOURCE_INSPECTION_FAILED);
        }
    }

    private static void requireFlywayEnabled(ConfigurableEnvironment environment) {
        boolean enabled;
        try {
            enabled = Binder.get(environment).bind("spring.flyway.enabled", Boolean.class).orElse(Boolean.TRUE);
        } catch (RuntimeException unresolvable) {
            throw reject(Reason.SETTINGS_UNRESOLVABLE);
        }
        if (!enabled) {
            throw reject(Reason.FLYWAY_DISABLED);
        }
    }

    private static boolean mayContain(ConfigurationPropertySource source, ConfigurationPropertyName prefix) {
        ConfigurationProperty direct = source.getConfigurationProperty(prefix);
        return direct != null || source.containsDescendantOf(prefix) != ConfigurationPropertyState.ABSENT;
    }

    private static boolean isRejectedName(ConfigurationPropertyName name) {
        if (DATASOURCE.equals(name) || FLYWAY.equals(name)) {
            return true;
        }
        if (DATASOURCE.isAncestorOf(name)) {
            if (ALLOWED_DATASOURCE.contains(name)) {
                return false;
            }
            return !(HIKARI.isAncestorOf(name) && ALLOWED_HIKARI.contains(name));
        }
        // Locations, schemas, callbacks, SQL, connection and every other Flyway option are rejected.
        return FLYWAY.isAncestorOf(name) && !FLYWAY_ENABLED.equals(name);
    }

    private static TestDatabaseGuardException reject(Reason reason) {
        return new TestDatabaseGuardException(Stage.INITIALIZER, reason);
    }
}
