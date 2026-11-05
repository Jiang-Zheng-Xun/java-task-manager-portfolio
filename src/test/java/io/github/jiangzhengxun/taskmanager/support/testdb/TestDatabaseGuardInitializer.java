package io.github.jiangzhengxun.taskmanager.support.testdb;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName.Form;
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
 * <p>Exactly four pgJDBC {@code spring.datasource.hikari.data-source-properties} keys are accepted, matched by their
 * original spelling, and only with the fixed values in {@link #DRIVER_TIMEOUTS}; {@code connection-timeout} is likewise
 * accepted only as {@code 10000}. Every property source is checked, so a conflicting value is rejected even when it is
 * shadowed. The guarded variant additionally requires all five settings to resolve to their fixed values before any
 * bean is created, and requires the controlled migration strategy in the bean structure.
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

    private static final ConfigurationPropertyName HIKARI_CONNECTION_TIMEOUT =
            ConfigurationPropertyName.of("spring.datasource.hikari.connection-timeout");
    private static final ConfigurationPropertyName HIKARI_DATA_SOURCE_PROPERTIES =
            ConfigurationPropertyName.of("spring.datasource.hikari.data-source-properties");

    static final String CONNECTION_TIMEOUT_MILLIS = "10000";

    // pgJDBC driver properties, in seconds. Keys are compared by their original spelling.
    static final Map<String, String> DRIVER_TIMEOUT_VALUES = Map.of(
            "connectTimeout", "5",
            "loginTimeout", "10",
            "socketTimeout", "15",
            "cancelSignalTimeout", "3");

    /** Settings the guarded entry point supplies before refresh; keys use Spring's relaxed property form. */
    public static final Map<String, Object> DRIVER_TIMEOUTS = Map.of(
            "spring.datasource.hikari.connection-timeout", CONNECTION_TIMEOUT_MILLIS,
            "spring.datasource.hikari.data-source-properties.connectTimeout", "5",
            "spring.datasource.hikari.data-source-properties.loginTimeout", "10",
            "spring.datasource.hikari.data-source-properties.socketTimeout", "15",
            "spring.datasource.hikari.data-source-properties.cancelSignalTimeout", "3");

    private final TestDatabaseGuardPolicy policy;
    private final boolean guarded;

    /** A null policy is rejected during initialization as {@code POLICY_MISSING}. */
    public TestDatabaseGuardInitializer(TestDatabaseGuardPolicy policy) {
        this(policy, false);
    }

    private TestDatabaseGuardInitializer(TestDatabaseGuardPolicy policy, boolean guarded) {
        this.policy = policy;
        this.guarded = guarded;
    }

    /**
     * Variant used by {@link GuardedTestDatabaseInitializer}: driver timeouts must resolve to their fixed values and
     * exactly the controlled {@link GuardedFlywayMigrationStrategy} definition is required.
     */
    static TestDatabaseGuardInitializer guarded(TestDatabaseGuardPolicy policy) {
        return new TestDatabaseGuardInitializer(policy, true);
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
        if (guarded) {
            requireDriverTimeouts(environment);
        }
        context.addBeanFactoryPostProcessor(new TestDatabaseBeanDefinitionGuard(guarded));
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
                        names.forEach(name -> checkName(iterable, name));
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

    private static void checkName(ConfigurationPropertySource source, ConfigurationPropertyName name) {
        if (isRejectedName(name)) {
            throw reject(Reason.ALTERNATE_SETTING_REJECTED);
        }
        String expected = fixedValue(name);
        if (expected != null) {
            // Every source is compared, including shadowed ones, so no conflicting value can be present anywhere.
            ConfigurationProperty property = source.getConfigurationProperty(name);
            Object value = property == null ? null : property.getValue();
            if (value == null || !expected.equals(String.valueOf(value))) {
                throw reject(Reason.DRIVER_TIMEOUT_REJECTED);
            }
        }
    }

    private static String fixedValue(ConfigurationPropertyName name) {
        if (HIKARI_CONNECTION_TIMEOUT.equals(name)) {
            return CONNECTION_TIMEOUT_MILLIS;
        }
        if (HIKARI_DATA_SOURCE_PROPERTIES.isParentOf(name)) {
            return DRIVER_TIMEOUT_VALUES.get(name.getLastElement(Form.ORIGINAL));
        }
        return null;
    }

    private static void requireDriverTimeouts(ConfigurableEnvironment environment) {
        Map<String, String> resolved;
        String connectionTimeout;
        try {
            Binder binder = Binder.get(environment);
            connectionTimeout = binder.bind(HIKARI_CONNECTION_TIMEOUT, Bindable.of(String.class)).orElse(null);
            resolved = binder.bind(HIKARI_DATA_SOURCE_PROPERTIES,
                    Bindable.mapOf(String.class, String.class))
                    .orElse(Map.of());
        } catch (RuntimeException unresolvable) {
            throw reject(Reason.SETTINGS_UNRESOLVABLE);
        }
        if (connectionTimeout == null || !resolved.keySet().containsAll(DRIVER_TIMEOUT_VALUES.keySet())) {
            throw reject(Reason.DRIVER_TIMEOUT_MISSING);
        }
        if (!CONNECTION_TIMEOUT_MILLIS.equals(connectionTimeout) || !DRIVER_TIMEOUT_VALUES.equals(resolved)) {
            throw reject(Reason.DRIVER_TIMEOUT_REJECTED);
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
            if (HIKARI_DATA_SOURCE_PROPERTIES.isParentOf(name)) {
                // Only the four fixed driver timeouts, by exact original spelling; values are checked separately.
                return !DRIVER_TIMEOUT_VALUES.containsKey(name.getLastElement(Form.ORIGINAL));
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
