package io.github.jiangzhengxun.taskmanager.support.testdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseConfigValidator.ExpectedTarget;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.Wrapper;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.migration.JavaMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.flyway.FlywayConnectionDetails;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * NoDB lifecycle tests for the registered guard.
 *
 * <p>Contexts use only synthetic property sources (system environment and system properties are removed) and only
 * {@link DataSourceAutoConfiguration} and {@link FlywayAutoConfiguration}. A fixture stage registered after the guard
 * aborts refresh before any bean is instantiated, so no DataSource, Hikari pool or Flyway instance is ever created.
 * The counting driver and fake migration action are fixture observers only; they are not native Flyway or Boot
 * evidence.
 */
class TestDatabaseGuardLifecycleTest {

    private static final String LOCAL_URL = "jdbc:postgresql://nodb-local.invalid:15432/nodb_local";
    private static final String LOCAL_USER = "nodb_local_user";
    private static final String CI_URL = "jdbc:postgresql://nodb-ci.invalid:25432/nodb_ci";
    private static final String CI_USER = "nodb_ci_user";
    private static final String SYNTHETIC_PASSWORD = "SyntheticSecretMarkerPw";

    private static final String EXTERNAL_SECRET = "ExternalSecretMarkerZq7";

    private static final AtomicInteger FIXTURE_BEAN_METHOD_CALLS = new AtomicInteger();
    private static final AtomicInteger FACTORY_BEAN_GET_OBJECT_CALLS = new AtomicInteger();
    private static final AtomicInteger FACTORY_BEAN_OBJECT_TYPE_CALLS = new AtomicInteger();

    private NextStageProbe probe;
    private CountingDriver driver;
    private FakeMigrationAction migration;

    @BeforeEach
    void resetObservers() {
        driver = new CountingDriver();
        migration = new FakeMigrationAction();
        probe = new NextStageProbe(driver, migration);
        FIXTURE_BEAN_METHOD_CALLS.set(0);
        FACTORY_BEAN_GET_OBJECT_CALLS.set(0);
        FACTORY_BEAN_OBJECT_TYPE_CALLS.set(0);
    }

    // ---- observer positive controls (fixture evidence only) ----

    @Test
    void countingDriverObserverCountsStubAndSyntheticFailureWithoutNetwork() throws SQLException {
        try (Connection stub = driver.connect(CountingDriver.STUB_URL, new Properties())) {
            assertThat(stub).isNotNull();
            assertThat(stub.isClosed()).isFalse();
        }
        assertThatThrownBy(() -> driver.connect(CountingDriver.FAILING_URL, new Properties()))
                .isInstanceOf(SQLException.class)
                .hasMessage(CountingDriver.SYNTHETIC_FAILURE);
        assertThat(driver.connect(LOCAL_URL, new Properties())).isNull();
        assertThat(driver.acceptsURL(LOCAL_URL)).isFalse();
        assertThat(driver.connectCalls()).isEqualTo(3);
    }

    @Test
    void fakeMigrationObserverCountsActions() {
        migration.run();
        migration.run();
        assertThat(migration.calls()).isEqualTo(2);
    }

    // ---- accepted settings reach the next fixture stage ----

    @Test
    void acceptedLocalSettingsReachNextFixtureStageWithoutInstantiation() {
        runner("local", LOCAL_URL, LOCAL_USER).run(context -> assertReachedNextStage(context.getStartupFailure()));
    }

    @Test
    void acceptedCiSettingsReachNextFixtureStageWithoutInstantiation() {
        runner("ci", CI_URL, CI_USER).run(context -> assertReachedNextStage(context.getStartupFailure()));
    }

    @Test
    void allowlistedHikariPoolSettingIsAccepted() {
        runner("local", LOCAL_URL, LOCAL_USER)
                .withPropertyValues("spring.datasource.hikari.maximum-pool-size=2")
                .run(context -> assertReachedNextStage(context.getStartupFailure()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.flyway.enabled=true", "spring.flyway.enabled=TRUE"})
    void explicitlyEnabledFlywayIsAccepted(String property) {
        runner("local", LOCAL_URL, LOCAL_USER)
                .withPropertyValues(property)
                .run(context -> assertReachedNextStage(context.getStartupFailure()));
    }

    @Test
    void enabledFlywayEnvironmentVariableIsAccepted() {
        ApplicationContextRunner runner = envRunner("SPRING_FLYWAY_ENABLED", "true", "spring.flyway.enabled", "true");
        runner.run(context -> assertReachedNextStage(context.getStartupFailure()));
    }

    // ---- Boot configuration properties binder factory (narrow FactoryBean exception) ----

    @Test
    void bootBinderFactoryHasReviewedStructureAndIsNeverInstantiated() {
        BinderFactoryRecorder recorder = new BinderFactoryRecorder();
        baseRunner()
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(context -> context.addBeanFactoryPostProcessor(recorder))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER))
                .run(context -> assertReachedNextStage(context.getStartupFailure()));
        assertThat(recorder.present).isTrue();
        assertThat(recorder.definitionClass).isEqualTo(RootBeanDefinition.class);
        assertThat(recorder.beanClassName).isEqualTo(TestDatabaseBeanDefinitionGuard.BOOT_BINDER_FACTORY_CLASS);
        assertThat(recorder.role).isEqualTo(BeanDefinition.ROLE_INFRASTRUCTURE);
        assertThat(recorder.factoryMethodName).isNull();
        assertThat(recorder.factoryBean).isTrue();
        // The guard accepted the definition without requesting, initializing or asking the factory for its object.
        assertThat(recorder.singletonCreated).isFalse();
    }

    @Test
    void binderNameWithDifferentFactoryBeanClassIsRejected() {
        // Boot's register() skips an existing name, so this definition stays in place of Boot's own.
        ApplicationContextRunner runner = baseRunner()
                .withInitializer(context -> ((BeanDefinitionRegistry) context.getBeanFactory()).registerBeanDefinition(
                        TestDatabaseBeanDefinitionGuard.BOOT_BINDER_FACTORY_BEAN_NAME,
                        infrastructure(new RootBeanDefinition(CountingFactoryBean.class))))
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        assertBeanDefinitionRejects(runner, Reason.FACTORY_BEAN_REJECTED);
        assertThat(FACTORY_BEAN_GET_OBJECT_CALLS).hasValue(0);
        assertThat(FACTORY_BEAN_OBJECT_TYPE_CALLS).hasValue(0);
    }

    @Test
    void binderNameAndClassWithDifferentRegistrationStructureIsRejected() {
        GenericBeanDefinition byName = new GenericBeanDefinition();
        byName.setBeanClassName(TestDatabaseBeanDefinitionGuard.BOOT_BINDER_FACTORY_CLASS);
        ApplicationContextRunner runner = baseRunner()
                .withInitializer(context -> ((BeanDefinitionRegistry) context.getBeanFactory()).registerBeanDefinition(
                        TestDatabaseBeanDefinitionGuard.BOOT_BINDER_FACTORY_BEAN_NAME, infrastructure(byName)))
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        assertBeanDefinitionRejects(runner, Reason.FACTORY_BEAN_REJECTED);
    }

    @Test
    void infrastructureRoleAloneDoesNotExemptFactoryBean() {
        ApplicationContextRunner runner = baseRunner()
                .withInitializer(context -> ((BeanDefinitionRegistry) context.getBeanFactory()).registerBeanDefinition(
                        "org.springframework.boot.context.fixtureInfrastructureFactory",
                        infrastructure(new RootBeanDefinition(CountingFactoryBean.class))))
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        assertBeanDefinitionRejects(runner, Reason.FACTORY_BEAN_REJECTED);
        assertThat(FACTORY_BEAN_GET_OBJECT_CALLS).hasValue(0);
        assertThat(FACTORY_BEAN_OBJECT_TYPE_CALLS).hasValue(0);
    }

    // ---- initializer rejections ----

    @Test
    void missingModeIsRejectedByInitializer() {
        assertInitializerRejects(runner(null, LOCAL_URL, LOCAL_USER), Reason.MODE_REJECTED);
    }

    @Test
    void invalidModeIsRejectedByInitializer() {
        assertInitializerRejects(runner("LOCAL", LOCAL_URL, LOCAL_USER), Reason.MODE_REJECTED);
    }

    @Test
    void unresolvableModePlaceholderIsRejectedByInitializer() {
        assertInitializerRejects(runner("${NODB_UNDEFINED_SYNTHETIC}", LOCAL_URL, LOCAL_USER),
                Reason.SETTINGS_UNRESOLVABLE);
    }

    @Test
    void missingPolicyIsRejectedByInitializer() {
        assertInitializerRejects(runner(null, "local", LOCAL_URL, LOCAL_USER), Reason.POLICY_MISSING);
    }

    @Test
    void policyWithoutSelectedModeIsRejectedByInitializer() {
        TestDatabaseGuardPolicy ciOnly = TestDatabaseGuardPolicy.of(Map.of(TestDatabaseMode.CI,
                new ExpectedTarget(CI_URL, CI_USER)));
        assertInitializerRejects(runner(ciOnly, "local", LOCAL_URL, LOCAL_USER), Reason.POLICY_MISSING);
    }

    @Test
    void invalidPolicyIsRejectedByInitializer() {
        TestDatabaseGuardPolicy invalid = TestDatabaseGuardPolicy.of(Map.of(TestDatabaseMode.LOCAL,
                new ExpectedTarget("jdbc:postgresql://nodb-local.invalid/nodb_local", LOCAL_USER)));
        assertInitializerRejects(runner(invalid, "local", LOCAL_URL, LOCAL_USER), Reason.POLICY_INVALID);
    }

    @Test
    void missingUrlIsRejectedByInitializer() {
        assertInitializerRejects(runner("local", null, LOCAL_USER), Reason.CONFIG_MISSING);
    }

    @Test
    void missingUsernameIsRejectedByInitializer() {
        assertInitializerRejects(runner("local", LOCAL_URL, null), Reason.CONFIG_MISSING);
    }

    @Test
    void unsupportedUrlIsRejectedByInitializer() {
        assertInitializerRejects(runner("local", "jdbc:postgresql://nodb-local.invalid:15432/nodb_local?ssl=true",
                LOCAL_USER), Reason.URL_UNSUPPORTED);
    }

    @Test
    void mismatchedUrlIsRejectedByInitializer() {
        assertInitializerRejects(runner("local", CI_URL, LOCAL_USER), Reason.TARGET_MISMATCH);
    }

    @Test
    void mismatchedUsernameIsRejectedByInitializer() {
        assertInitializerRejects(runner("local", LOCAL_URL, CI_USER), Reason.TARGET_MISMATCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "spring.flyway.url=jdbc:postgresql://nodb-other.invalid:35432/nodb_other",
        "spring.flyway.user=nodb_other_user",
        "spring.flyway.password=SyntheticSecretMarkerPw",
        "spring.flyway.driver-class-name=org.postgresql.Driver",
        "spring.flyway.jdbc-properties.ssl=false",
        "spring.datasource.hikari.jdbc-url=jdbc:postgresql://nodb-other.invalid:35432/nodb_other",
        "spring.datasource.hikari.username=nodb_other_user",
        "spring.datasource.hikari.password=SyntheticSecretMarkerPw",
        "spring.datasource.hikari.data-source-class-name=org.postgresql.ds.PGSimpleDataSource",
        "spring.datasource.hikari.data-source-properties.serverName=nodb-other.invalid",
        "spring.datasource.hikari.driver-class-name=org.postgresql.Driver",
        "spring.datasource.hikari.data-source-j-n-d-i=java:comp/env/nodb",
        "spring.datasource.hikari.connection-init-sql=SELECT 1",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.datasource.jndi-name=java:comp/env/nodb",
        "spring.datasource.type=com.zaxxer.hikari.HikariDataSource",
        "spring.datasource.xa.data-source-class-name=org.postgresql.xa.PGXADataSource",
        "spring.datasource.embedded-database-connection=none"
    })
    void alternateTargetSettingIsRejectedByInitializer(String property) {
        assertInitializerRejects(runner("local", LOCAL_URL, LOCAL_USER).withPropertyValues(property),
                Reason.ALTERNATE_SETTING_REJECTED);
    }

    @Test
    void relaxedEnvironmentVariableFormIsRejectedByInitializer() {
        ApplicationContextRunner runner = baseRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                            new SystemEnvironmentPropertySource("synthetic-systemEnvironment", Map.<String, Object>of(
                                    "SPRING_DATASOURCE_HIKARI_JDBCURL",
                                    "jdbc:postgresql://nodb-other.invalid:35432/nodb_other")));
                    assertThat(Binder.get(context.getEnvironment())
                            .bind("spring.datasource.hikari.jdbc-url", String.class).orElse(null))
                            .isEqualTo("jdbc:postgresql://nodb-other.invalid:35432/nodb_other");
                })
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        assertInitializerRejects(runner, Reason.ALTERNATE_SETTING_REJECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "spring.flyway.locations=classpath:nodb/other",
        "spring.flyway.schemas=nodb_other",
        "spring.flyway.default-schema=nodb_other",
        "spring.flyway.callbacks=nodb.fixture.Callback",
        "spring.flyway.init-sqls=SELECT 1",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.clean-disabled=false",
        "spring.flyway.placeholders.nodb=other",
        "spring.flyway.sql-migration-prefix=X",
        "spring.flyway.target=1",
        "spring.flyway.fail-on-missing-locations=false",
        "spring.flyway=nodb-other",
        "spring.datasource=nodb-other"
    })
    void forbiddenFlywaySettingIsRejectedByInitializer(String property) {
        assertInitializerRejects(runner("local", LOCAL_URL, LOCAL_USER).withPropertyValues(property),
                Reason.ALTERNATE_SETTING_REJECTED);
    }

    @Test
    void forbiddenFlywaySettingInLowerPrioritySourceIsRejectedByInitializer() {
        ApplicationContextRunner runner = baseRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addLast(
                        new MapPropertySource("synthetic-lowest", Map.<String, Object>of(
                                "spring.flyway.locations", "classpath:nodb/other"))))
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        assertInitializerRejects(runner, Reason.ALTERNATE_SETTING_REJECTED);
    }

    @Test
    void forbiddenFlywayEnvironmentVariableIsRejectedByInitializer() {
        assertInitializerRejects(envRunner("SPRING_FLYWAY_LOCATIONS", "classpath:nodb/other",
                "spring.flyway.locations", "classpath:nodb/other"), Reason.ALTERNATE_SETTING_REJECTED);
    }

    @Test
    void disabledFlywayEnvironmentVariableIsRejectedByInitializer() {
        assertInitializerRejects(envRunner("SPRING_FLYWAY_ENABLED", "false", "spring.flyway.enabled", "false"),
                Reason.FLYWAY_DISABLED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"spring.flyway.enabled=maybe", "spring.flyway.enabled=${NODB_UNDEFINED_SYNTHETIC}"})
    void invalidOrUnresolvableFlywayEnabledIsRejectedByInitializer(String property) {
        assertInitializerRejects(runner("local", LOCAL_URL, LOCAL_USER).withPropertyValues(property),
                Reason.SETTINGS_UNRESOLVABLE);
    }

    @Test
    void nonEnumerablePropertySourceIsRejectedByInitializer() {
        ApplicationContextRunner runner = baseRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new OpaquePropertySource()))
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        assertInitializerRejects(runner, Reason.PROPERTY_SOURCE_UNVERIFIABLE);
    }

    // ---- bean definition (BFPP) rejections ----

    @Test
    void missingAutoConfiguredStructureIsRejectedByBeanDefinitionGuard() {
        ApplicationContextRunner runner = new ApplicationContextRunner(TestDatabaseGuardLifecycleTest::syntheticContext)
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        assertBeanDefinitionRejects(runner, Reason.DATASOURCE_UNSUPPORTED);
    }

    @Test
    void customDataSourceIsRejectedByBeanDefinitionGuard() {
        assertBeanDefinitionRejects(runner("local", LOCAL_URL, LOCAL_USER)
                .withUserConfiguration(CustomDataSourceFixture.class), Reason.DATASOURCE_UNSUPPORTED);
    }

    @Test
    void preRegisteredSingletonDataSourceIsRejectedByBeanDefinitionGuard() {
        ApplicationContextRunner runner = baseRunner()
                .withInitializer(context -> context.getBeanFactory().registerSingleton("fixtureDataSource",
                        Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                                (proxy, method, args) -> {
                                    throw new UnsupportedOperationException("NODB_FIXTURE");
                                })))
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        assertBeanDefinitionRejects(runner, Reason.DATASOURCE_UNSUPPORTED);
    }

    @Test
    void customJdbcConnectionDetailsAreRejectedByBeanDefinitionGuard() {
        assertBeanDefinitionRejects(runner("local", LOCAL_URL, LOCAL_USER)
                .withUserConfiguration(CustomJdbcConnectionDetailsFixture.class),
                Reason.CONNECTION_DETAILS_UNSUPPORTED);
    }

    @Test
    void customFlywayIsRejectedByBeanDefinitionGuard() {
        assertBeanDefinitionRejects(runner("local", LOCAL_URL, LOCAL_USER)
                .withUserConfiguration(CustomFlywayFixture.class), Reason.FLYWAY_UNSUPPORTED);
    }

    @Test
    void disabledFlywayIsRejectedByInitializer() {
        // Hardening 03: an effective spring.flyway.enabled=false is now rejected before refresh.
        assertInitializerRejects(runner("local", LOCAL_URL, LOCAL_USER)
                .withPropertyValues("spring.flyway.enabled=false"), Reason.FLYWAY_DISABLED);
    }

    @Test
    void missingFlywayAutoConfigurationIsRejectedByBeanDefinitionGuard() {
        ApplicationContextRunner runner = new ApplicationContextRunner(TestDatabaseGuardLifecycleTest::syntheticContext)
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        assertBeanDefinitionRejects(runner, Reason.FLYWAY_UNSUPPORTED);
    }

    @Test
    void unknownFactoryReturnTypeIsRejectedByBeanDefinitionGuard() {
        assertBeanDefinitionRejects(runner("local", LOCAL_URL, LOCAL_USER)
                .withUserConfiguration(ObjectReturnTypeFixture.class), Reason.DEFINITION_UNVERIFIABLE);
    }

    @Test
    void guardedSupertypeFactoryReturnTypeIsRejectedByBeanDefinitionGuard() {
        assertBeanDefinitionRejects(runner("local", LOCAL_URL, LOCAL_USER)
                .withUserConfiguration(WrapperReturnTypeFixture.class), Reason.DEFINITION_UNVERIFIABLE);
    }

    @Test
    void factoryBeanMethodIsRejectedByBeanDefinitionGuard() {
        assertBeanDefinitionRejects(runner("local", LOCAL_URL, LOCAL_USER)
                .withUserConfiguration(FactoryBeanMethodFixture.class), Reason.FACTORY_BEAN_REJECTED);
    }

    @Test
    void factoryBeanClassDefinitionIsRejectedByBeanDefinitionGuard() {
        ApplicationContextRunner runner = baseRunner()
                .withInitializer(context -> ((BeanDefinitionRegistry) context.getBeanFactory()).registerBeanDefinition(
                        "fixtureFactoryBean", new RootBeanDefinition(CountingFactoryBean.class)))
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        assertBeanDefinitionRejects(runner, Reason.FACTORY_BEAN_REJECTED);
        assertThat(FACTORY_BEAN_GET_OBJECT_CALLS).hasValue(0);
        assertThat(FACTORY_BEAN_OBJECT_TYPE_CALLS).hasValue(0);
    }

    @Test
    void preRegisteredSingletonFactoryBeanIsRejectedByBeanDefinitionGuard() {
        // Spring's own post-processor discovery may call getObjectType() on a manual singleton before any ordinary
        // post-processor runs; only getObject() is asserted here.
        ApplicationContextRunner runner = baseRunner()
                .withInitializer(context -> context.getBeanFactory().registerSingleton("fixtureFactoryBean",
                        new CountingFactoryBean(true)))
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        assertBeanDefinitionRejects(runner, Reason.FACTORY_BEAN_REJECTED);
        assertThat(FACTORY_BEAN_GET_OBJECT_CALLS).hasValue(0);
    }

    @Test
    void customFlywayConnectionDetailsAreRejectedByBeanDefinitionGuard() {
        assertBeanDefinitionRejects(runner("local", LOCAL_URL, LOCAL_USER)
                .withUserConfiguration(CustomFlywayConnectionDetailsFixture.class),
                Reason.FLYWAY_CONNECTION_DETAILS_UNSUPPORTED);
    }

    @Test
    void migrationStrategyIsRejectedByBeanDefinitionGuard() {
        assertBeanDefinitionRejects(runner("local", LOCAL_URL, LOCAL_USER)
                .withUserConfiguration(MigrationStrategyFixture.class), Reason.MIGRATION_STRATEGY_REJECTED);
    }

    @Test
    void customFlywayCustomizerIsRejectedByBeanDefinitionGuard() {
        assertBeanDefinitionRejects(runner("local", LOCAL_URL, LOCAL_USER)
                .withUserConfiguration(FlywayCustomizerFixture.class), Reason.FLYWAY_CUSTOMIZER_REJECTED);
    }

    @Test
    void javaMigrationIsRejectedByBeanDefinitionGuard() {
        assertBeanDefinitionRejects(runner("local", LOCAL_URL, LOCAL_USER)
                .withUserConfiguration(JavaMigrationFixture.class), Reason.FLYWAY_EXTENSION_REJECTED);
    }

    @Test
    void flywayCallbackIsRejectedByBeanDefinitionGuard() {
        assertBeanDefinitionRejects(runner("local", LOCAL_URL, LOCAL_USER)
                .withUserConfiguration(FlywayCallbackFixture.class), Reason.FLYWAY_EXTENSION_REJECTED);
    }

    // ---- redacted diagnostics ----

    @Test
    void initializerDiagnosticsAndPolicyAreRedacted() {
        String secretHost = "secret-host-marker.invalid";
        String secretUser = "secret_user_marker";
        TestDatabaseGuardPolicy policy = defaultPolicy();
        runner(policy, "local", "jdbc:postgresql://" + secretHost + ":15432/nodb_local", secretUser)
                .run(context -> {
                    Throwable failure = context.getStartupFailure();
                    assertThat(failure).isInstanceOf(TestDatabaseGuardException.class);
                    assertRedacted(failure, secretHost, secretUser, SYNTHETIC_PASSWORD, LOCAL_URL, LOCAL_USER);
                });
        assertThat(policy.toString()).isEqualTo("TestDatabaseGuardPolicy[redacted]");
    }

    @Test
    void alternateSettingDiagnosticsAreRedacted() {
        runner("local", LOCAL_URL, LOCAL_USER)
                .withPropertyValues("spring.datasource.hikari.password=AltSecretMarkerPw")
                .run(context -> assertRedacted(context.getStartupFailure(), "AltSecretMarkerPw",
                        SYNTHETIC_PASSWORD, LOCAL_URL, LOCAL_USER, "hikari"));
    }

    @Test
    void beanDefinitionDiagnosticsAreRedacted() {
        runner("local", LOCAL_URL, LOCAL_USER)
                .withUserConfiguration(CustomDataSourceFixture.class)
                .run(context -> assertRedacted(context.getStartupFailure(), SYNTHETIC_PASSWORD, LOCAL_URL,
                        LOCAL_USER, "fixtureDataSource"));
    }

    // ---- external exceptions are replaced by fixed diagnostics ----

    @Test
    void externalResolutionExceptionIsReplacedByFixedDiagnostic() {
        ApplicationContextRunner runner = baseRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new ThrowingQuerySource("spring.datasource.url")))
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        // Boot's adapter swallows the query exception (see bootAdapterSwallowsPropertySourceQueryException), so the
        // Binder falls through; the non-enumerable source is still rejected as unverifiable.
        assertExternalReplaced(runner, Stage.INITIALIZER, Reason.PROPERTY_SOURCE_UNVERIFIABLE);
    }

    @Test
    void externalFlywayEnabledResolutionExceptionIsReplacedByFixedDiagnostic() {
        ApplicationContextRunner runner = baseRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new ThrowingValueEnumerableSource("spring.flyway.enabled")))
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        assertExternalReplaced(runner, Stage.INITIALIZER, Reason.SETTINGS_UNRESOLVABLE);
    }

    @Test
    void externalEnumerationExceptionIsReplacedByFixedDiagnostic() {
        ApplicationContextRunner runner = baseRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new ThrowingEnumerationSource()))
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        assertExternalReplaced(runner, Stage.INITIALIZER, Reason.PROPERTY_SOURCE_INSPECTION_FAILED);
    }

    @Test
    void externalPropertySourceQueryExceptionIsReplacedByFixedDiagnostic() {
        ApplicationContextRunner runner = baseRunner()
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new ThrowingQuerySource("spring.datasource")))
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        // The swallowed query answers null; containsDescendantOf stays unknown, so the source is unverifiable.
        assertExternalReplaced(runner, Stage.INITIALIZER, Reason.PROPERTY_SOURCE_UNVERIFIABLE);
    }

    @Test
    void bootAdapterSwallowsPropertySourceQueryException() {
        // Precondition for the two query cases above: Boot's adapter, not the guard, turns the exception into null.
        ConfigurationPropertySource adapter =
                ConfigurationPropertySource.from(new ThrowingQuerySource("spring.datasource.url"));
        assertThat(adapter.getConfigurationProperty(ConfigurationPropertyName.of("spring.datasource.url"))).isNull();
    }

    @Test
    void externalDefinitionInspectionExceptionIsReplacedByFixedDiagnostic() {
        GenericBeanDefinition unloadable = new GenericBeanDefinition();
        unloadable.setBeanClassName("nodb.fixture.missing." + EXTERNAL_SECRET);
        ApplicationContextRunner runner = baseRunner()
                .withInitializer(context -> ((BeanDefinitionRegistry) context.getBeanFactory()).registerBeanDefinition(
                        "fixtureUnloadable", unloadable))
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
        assertExternalReplaced(runner, Stage.BEAN_DEFINITION, Reason.DEFINITION_INSPECTION_FAILED);
    }

    @Test
    void guardRejectionsInsideWrappedStagesKeepTheirCategory() {
        // Raised inside the wrapped enumeration and definition-inspection blocks; must not become *_FAILED.
        assertInitializerRejects(runner("local", LOCAL_URL, LOCAL_USER)
                .withPropertyValues("spring.flyway.locations=classpath:nodb/other"), Reason.ALTERNATE_SETTING_REJECTED);
        assertBeanDefinitionRejects(runner("local", LOCAL_URL, LOCAL_USER)
                .withUserConfiguration(ObjectReturnTypeFixture.class), Reason.DEFINITION_UNVERIFIABLE);
        assertBeanDefinitionRejects(runner("local", LOCAL_URL, LOCAL_USER)
                .withUserConfiguration(CustomDataSourceFixture.class), Reason.DATASOURCE_UNSUPPORTED);
    }

    // ---- helpers ----

    private ApplicationContextRunner envRunner(String variable, String value, String mappedName,
            String expectedBound) {
        return baseRunner()
                .withInitializer(context -> {
                    context.getEnvironment().getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                            "synthetic-systemEnvironment", Map.<String, Object>of(variable, value)));
                    // Precondition: Boot's Binder maps the environment-variable spelling to the canonical name.
                    assertThat(Binder.get(context.getEnvironment()).bind(mappedName, String.class).orElse(null))
                            .isEqualTo(expectedBound);
                })
                .withInitializer(new TestDatabaseGuardInitializer(defaultPolicy()))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings("local", LOCAL_URL, LOCAL_USER));
    }

    private void assertExternalReplaced(ApplicationContextRunner runner, Stage stage, Reason reason) {
        // One context run checks both the fixed reason and the redaction.
        assertRejects(runner, stage, reason, EXTERNAL_SECRET, SYNTHETIC_PASSWORD, LOCAL_URL, LOCAL_USER);
    }

    private static BeanDefinition infrastructure(BeanDefinition definition) {
        definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        return definition;
    }

    private static ConfigurableApplicationContext syntheticContext() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        MutablePropertySources sources = context.getEnvironment().getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        return context;
    }

    private static ApplicationContextRunner baseRunner() {
        return new ApplicationContextRunner(TestDatabaseGuardLifecycleTest::syntheticContext)
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        FlywayAutoConfiguration.class));
    }

    private static TestDatabaseGuardPolicy defaultPolicy() {
        Map<TestDatabaseMode, ExpectedTarget> targets = new EnumMap<>(TestDatabaseMode.class);
        targets.put(TestDatabaseMode.LOCAL, new ExpectedTarget(LOCAL_URL, LOCAL_USER));
        targets.put(TestDatabaseMode.CI, new ExpectedTarget(CI_URL, CI_USER));
        return TestDatabaseGuardPolicy.of(targets);
    }

    private ApplicationContextRunner runner(String mode, String url, String username) {
        return runner(defaultPolicy(), mode, url, username);
    }

    private ApplicationContextRunner runner(TestDatabaseGuardPolicy policy, String mode, String url, String username) {
        return baseRunner()
                .withInitializer(new TestDatabaseGuardInitializer(policy))
                .withInitializer(this::registerProbe)
                .withPropertyValues(settings(mode, url, username));
    }

    private static String[] settings(String mode, String url, String username) {
        return new String[] {
            mode == null ? "nodb.fixture.mode-absent=true" : TestDatabaseGuardInitializer.MODE_PROPERTY + "=" + mode,
            url == null ? "nodb.fixture.url-absent=true" : "spring.datasource.url=" + url,
            username == null ? "nodb.fixture.username-absent=true" : "spring.datasource.username=" + username,
            "spring.datasource.password=" + SYNTHETIC_PASSWORD
        };
    }

    private void registerProbe(ConfigurableApplicationContext context) {
        // Registered after the guard initializer, so it runs after the guard post-processor.
        context.addBeanFactoryPostProcessor(probe);
    }

    private void assertReachedNextStage(Throwable failure) {
        assertThat(failure).isInstanceOf(NextStageReached.class);
        assertThat(probe.calls()).isEqualTo(1);
        assertThat(probe.bootStructurePresent()).isTrue();
        assertThat(probe.guardedSingletonsCreated()).isFalse();
        assertThat(driver.connectCalls()).isEqualTo(1);
        assertThat(migration.calls()).isEqualTo(1);
        assertThat(FIXTURE_BEAN_METHOD_CALLS).hasValue(0);
    }

    private void assertInitializerRejects(ApplicationContextRunner runner, Reason reason) {
        assertRejects(runner, Stage.INITIALIZER, reason);
    }

    private void assertBeanDefinitionRejects(ApplicationContextRunner runner, Reason reason) {
        assertRejects(runner, Stage.BEAN_DEFINITION, reason);
    }

    private void assertRejects(ApplicationContextRunner runner, Stage stage, Reason reason,
            String... redactedMarkers) {
        runner.run(context -> {
            Throwable failure = context.getStartupFailure();
            assertThat(failure).isInstanceOf(TestDatabaseGuardException.class);
            TestDatabaseGuardException rejection = (TestDatabaseGuardException) failure;
            assertThat(rejection.stage()).isEqualTo(stage);
            assertThat(rejection.reason()).isEqualTo(reason);
            assertThat(rejection.getCause()).isNull();
            assertThat(rejection.getSuppressed()).isEmpty();
            assertRedacted(failure, redactedMarkers);
        });
        assertThat(probe.calls()).isZero();
        assertThat(driver.connectCalls()).isZero();
        assertThat(migration.calls()).isZero();
        assertThat(FIXTURE_BEAN_METHOD_CALLS).hasValue(0);
    }

    private static void assertRedacted(Throwable failure, String... markers) {
        assertThat(failure).isInstanceOf(TestDatabaseGuardException.class);
        assertThat(failure.getCause()).isNull();
        for (String marker : markers) {
            assertThat(failure.getMessage()).doesNotContain(marker);
            assertThat(failure.toString()).doesNotContain(marker);
        }
    }

    private static <T> T neverInstantiated() {
        FIXTURE_BEAN_METHOD_CALLS.incrementAndGet();
        throw new AssertionError("NODB_FIXTURE_BEAN_INSTANTIATED");
    }

    // ---- fixtures ----

    /** Thrown by the fixture stage to stop refresh before any bean instantiation. */
    static final class NextStageReached extends RuntimeException {
        NextStageReached() {
            super("NODB_FIXTURE_NEXT_STAGE_REACHED", null, false, false);
        }
    }

    /** Fixture stage after the guard: exercises the observers, records state, then aborts refresh. */
    static final class NextStageProbe implements BeanFactoryPostProcessor {
        private final CountingDriver driver;
        private final FakeMigrationAction migration;
        private int calls;
        private boolean bootStructurePresent;
        private boolean guardedSingletonsCreated;

        NextStageProbe(CountingDriver driver, FakeMigrationAction migration) {
            this.driver = driver;
            this.migration = migration;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            calls++;
            String[] dataSources = beanFactory.getBeanNamesForType(DataSource.class, true, false);
            String[] flyways = beanFactory.getBeanNamesForType(Flyway.class, true, false);
            bootStructurePresent = dataSources.length == 1 && flyways.length == 1;
            for (String name : dataSources) {
                guardedSingletonsCreated |= beanFactory.containsSingleton(name);
            }
            for (String name : flyways) {
                guardedSingletonsCreated |= beanFactory.containsSingleton(name);
            }
            try (Connection ignored = driver.connect(CountingDriver.STUB_URL, new Properties())) {
                migration.run();
            } catch (SQLException unexpected) {
                throw new IllegalStateException("NODB_FIXTURE_STUB_FAILED");
            }
            throw new NextStageReached();
        }

        int calls() {
            return calls;
        }

        boolean bootStructurePresent() {
            return bootStructurePresent;
        }

        boolean guardedSingletonsCreated() {
            return guardedSingletonsCreated;
        }
    }

    /**
     * Counting JDBC driver that accepts only {@code jdbc:nodb-fixture:} URLs. It is never registered with
     * DriverManager, never delegates to PostgreSQL and never opens a socket.
     */
    static final class CountingDriver implements Driver {
        static final String STUB_URL = "jdbc:nodb-fixture:stub";
        static final String FAILING_URL = "jdbc:nodb-fixture:fail";
        static final String SYNTHETIC_FAILURE = "NODB_FIXTURE_SYNTHETIC_FAILURE";

        private final AtomicInteger connectCalls = new AtomicInteger();

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            connectCalls.incrementAndGet();
            if (STUB_URL.equals(url)) {
                return stubConnection();
            }
            if (FAILING_URL.equals(url)) {
                throw new SQLException(SYNTHETIC_FAILURE);
            }
            return null;
        }

        private static Connection stubConnection() {
            boolean[] closed = {false};
            return (Connection) Proxy.newProxyInstance(CountingDriver.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "close" -> {
                            closed[0] = true;
                            yield null;
                        }
                        case "isClosed" -> closed[0];
                        case "toString" -> "NoDbStubConnection";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException("NODB_FIXTURE_STUB");
                    });
        }

        int connectCalls() {
            return connectCalls.get();
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith("jdbc:nodb-fixture:");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 0;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger(CountingDriver.class.getName());
        }
    }

    /** Fake migration action; it never calls Flyway. */
    static final class FakeMigrationAction implements Runnable {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void run() {
            calls.incrementAndGet();
        }

        int calls() {
            return calls.get();
        }
    }

    /** Non-enumerable source that may contain anything; the guard cannot verify it. */
    static final class OpaquePropertySource extends PropertySource<Object> {
        OpaquePropertySource() {
            super("opaqueFixture", new Object());
        }

        @Override
        public Object getProperty(String name) {
            return null;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomDataSourceFixture {
        @Bean
        DataSource fixtureDataSource() {
            return neverInstantiated();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomJdbcConnectionDetailsFixture {
        @Bean
        JdbcConnectionDetails fixtureJdbcConnectionDetails() {
            return neverInstantiated();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomFlywayFixture {
        @Bean
        Flyway fixtureFlyway() {
            return neverInstantiated();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomFlywayConnectionDetailsFixture {
        @Bean
        FlywayConnectionDetails fixtureFlywayConnectionDetails() {
            return neverInstantiated();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MigrationStrategyFixture {
        @Bean
        FlywayMigrationStrategy fixtureMigrationStrategy() {
            return neverInstantiated();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FlywayCustomizerFixture {
        @Bean
        FlywayConfigurationCustomizer fixtureFlywayCustomizer() {
            return neverInstantiated();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class JavaMigrationFixture {
        @Bean
        JavaMigration fixtureJavaMigration() {
            return neverInstantiated();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FlywayCallbackFixture {
        @Bean
        Callback fixtureFlywayCallback() {
            return neverInstantiated();
        }
    }

    /** Non-enumerable source whose query for one exact name fails with a secret-bearing external message. */
    static final class ThrowingQuerySource extends PropertySource<Object> {
        private final String failingName;

        ThrowingQuerySource(String failingName) {
            super("throwingQueryFixture", new Object());
            this.failingName = failingName;
        }

        @Override
        public Object getProperty(String name) {
            if (failingName.equals(name)) {
                throw new IllegalStateException("query failed " + EXTERNAL_SECRET);
            }
            return null;
        }
    }

    /** Enumerable source whose value lookup for one listed name fails with a secret-bearing external message. */
    static final class ThrowingValueEnumerableSource extends EnumerablePropertySource<Object> {
        private final String failingName;

        ThrowingValueEnumerableSource(String failingName) {
            super("throwingValueFixture", new Object());
            this.failingName = failingName;
        }

        @Override
        public String[] getPropertyNames() {
            return new String[] {failingName};
        }

        @Override
        public Object getProperty(String name) {
            if (failingName.equals(name)) {
                throw new IllegalStateException("value failed " + EXTERNAL_SECRET);
            }
            return null;
        }
    }

    /**
     * Enumerable source that fails only when Boot streams its names (the guard's enumeration stage), so settings
     * resolution earlier in the same initializer still succeeds.
     */
    static final class ThrowingEnumerationSource extends EnumerablePropertySource<Object> {
        ThrowingEnumerationSource() {
            super("throwingEnumerationFixture", new Object());
        }

        @Override
        public String[] getPropertyNames() {
            boolean streaming = StackWalker.getInstance().walk(frames -> frames.anyMatch(frame ->
                    frame.getClassName().endsWith(".SpringIterableConfigurationPropertySource")
                            && frame.getMethodName().equals("stream")));
            if (streaming) {
                throw new IllegalStateException("enumeration failed " + EXTERNAL_SECRET);
            }
            return new String[0];
        }

        @Override
        public Object getProperty(String name) {
            return null;
        }
    }

    /** Fixture stage between guard and probe: records Boot's binder factory definition without requesting it. */
    static final class BinderFactoryRecorder implements BeanFactoryPostProcessor {
        boolean present;
        Class<?> definitionClass;
        String beanClassName;
        int role = -1;
        String factoryMethodName;
        boolean factoryBean;
        boolean singletonCreated;

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            String name = TestDatabaseBeanDefinitionGuard.BOOT_BINDER_FACTORY_BEAN_NAME;
            present = beanFactory.containsBeanDefinition(name);
            if (!present) {
                return;
            }
            BeanDefinition definition = beanFactory.getBeanDefinition(name);
            definitionClass = definition.getClass();
            beanClassName = definition.getBeanClassName();
            role = definition.getRole();
            factoryMethodName = definition.getFactoryMethodName();
            factoryBean = beanFactory.isFactoryBean(name);
            singletonCreated = beanFactory.containsSingleton(name);
        }
    }

    /** FactoryBean whose construction by Spring and every FactoryBean method are counted as violations. */
    static final class CountingFactoryBean implements FactoryBean<DataSource> {
        public CountingFactoryBean() {
            FIXTURE_BEAN_METHOD_CALLS.incrementAndGet();
            throw new AssertionError("NODB_FIXTURE_FACTORY_BEAN_INSTANTIATED");
        }

        CountingFactoryBean(boolean manual) {
        }

        @Override
        public DataSource getObject() {
            FACTORY_BEAN_GET_OBJECT_CALLS.incrementAndGet();
            throw new AssertionError("NODB_FIXTURE_FACTORY_BEAN_GET_OBJECT");
        }

        @Override
        public Class<?> getObjectType() {
            FACTORY_BEAN_OBJECT_TYPE_CALLS.incrementAndGet();
            return null;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ObjectReturnTypeFixture {
        @Bean
        Object fixtureUnknown() {
            return neverInstantiated();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class WrapperReturnTypeFixture {
        @Bean
        Wrapper fixtureWrapper() {
            return neverInstantiated();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FactoryBeanMethodFixture {
        @Bean
        static FactoryBean<DataSource> fixtureDataSourceFactory() {
            return neverInstantiated();
        }
    }
}
