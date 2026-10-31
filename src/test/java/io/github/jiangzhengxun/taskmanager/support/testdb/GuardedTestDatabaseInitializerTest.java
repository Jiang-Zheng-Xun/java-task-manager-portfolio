package io.github.jiangzhengxun.taskmanager.support.testdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.context.ContextConfiguration;

/**
 * Policy loading, the per-context policy snapshot and pre-refresh settings for the guarded entry point. Uses synthetic
 * policy files, property sources and Mockito JDBC doubles only; contexts are never refreshed and no database is
 * contacted.
 */
class GuardedTestDatabaseInitializerTest {

    private static final String URL = "jdbc:postgresql://synthetic-local.invalid:15432/synthetic_db";
    private static final String USER = "synthetic_login";
    private static final String PASSWORD = "SyntheticSecretMarkerPw";
    private static final String OTHER_URL = "jdbc:postgresql://synthetic-other.invalid:25432/synthetic_other_db";

    @TempDir
    Path directory;

    // ---- annotation and entry structure ----

    @Test
    void annotationAppliesNoArgumentInitializerAndControlledConfiguration() {
        ContextConfiguration configuration =
                AnnotatedElementUtils.findMergedAnnotation(Annotated.class, ContextConfiguration.class);
        assertThat(configuration.initializers()).containsExactly(GuardedTestDatabaseInitializer.class);
        Import imported = AnnotatedElementUtils.findMergedAnnotation(Annotated.class, Import.class);
        assertThat(imported.value()).containsExactly(GuardedTestDatabaseConfiguration.class);
    }

    @Test
    void configurationDeclaresExactlyOneConcreteStrategyFactoryMethod() {
        Method[] methods = GuardedTestDatabaseConfiguration.class.getDeclaredMethods();
        assertThat(methods).hasSize(1);
        Method method = methods[0];
        assertThat(method.getName()).isEqualTo(TestDatabaseBeanDefinitionGuard.GUARDED_STRATEGY_FACTORY_METHOD);
        assertThat(method.getReturnType()).isEqualTo(GuardedFlywayMigrationStrategy.class);
        assertThat(Modifier.isStatic(method.getModifiers())).isFalse();
        assertThat(method.isAnnotationPresent(Bean.class)).isTrue();
        // Only the context's own registry is taken; no Environment, so the policy cannot be resolved again.
        assertThat(method.getParameterTypes()).containsExactly(ConfigurableListableBeanFactory.class);
    }

    // ---- policy loading ----

    @Test
    void localPolicyIsLoadedFromPrivateFileIndependentlyOfDatasourceSettings() throws IOException {
        TestDatabasePolicyProvider.Resolved resolved =
                TestDatabasePolicyProvider.resolve("local", writePolicy(validFields()).toString());
        assertThat(resolved.mode()).isEqualTo(TestDatabaseMode.LOCAL);
        assertThat(resolved.identity().database()).isEqualTo("synthetic_db");
        assertThat(resolved.identity().loginRole()).isEqualTo(USER);
        assertThat(resolved.identity().effectiveRole()).isEqualTo(USER);
        assertThat(resolved.identity().ownerRole()).isEqualTo("synthetic_owner");
        assertThat(resolved.guardPolicy().targets()).containsOnlyKeys(TestDatabaseMode.LOCAL);
        assertThat(resolved.toString()).doesNotContain("synthetic");
    }

    @Test
    void ciUsesFixedIndependentPolicy() {
        TestDatabasePolicyProvider.Resolved resolved = TestDatabasePolicyProvider.resolve("ci", null);
        assertThat(resolved.identity().database()).isEqualTo("task_manager_test");
        assertThat(resolved.identity().loginRole()).isEqualTo("jtm_test");
        assertThat(resolved.identity().effectiveRole()).isEqualTo("jtm_test");
        assertThat(resolved.identity().ownerRole()).isEqualTo("jtm_test");
        assertThat(resolved.guardPolicy().targets().get(TestDatabaseMode.CI).jdbcUrl())
                .isEqualTo("jdbc:postgresql://localhost:5432/task_manager_test");
        assertThat(resolved.guardPolicy().targets().get(TestDatabaseMode.CI).username()).isEqualTo("jtm_test");
    }

    @Test
    void ciRejectsPolicyFile() throws IOException {
        String file = writePolicy(validFields()).toString();
        assertPolicyRejects(() -> TestDatabasePolicyProvider.resolve("ci", file),
                Reason.POLICY_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "LOCAL", "prod"})
    void unknownModeIsRejected(String mode) {
        assertPolicyRejects(() -> TestDatabasePolicyProvider.resolve(mode, null), Reason.MODE_REJECTED);
    }

    @Test
    void missingPolicyFileSettingIsRejected() {
        assertPolicyRejects(() -> TestDatabasePolicyProvider.resolve("local", null), Reason.POLICY_FILE_MISSING);
    }

    @Test
    void absentPolicyFileIsRejected() {
        assertPolicyRejects(() -> TestDatabasePolicyProvider.resolve("local",
                directory.resolve("absent.properties").toString()), Reason.POLICY_FILE_MISSING);
    }

    @Test
    void relativePolicyFileIsRejected() {
        assertPolicyRejects(() -> TestDatabasePolicyProvider.resolve("local", "policy.properties"),
                Reason.POLICY_FILE_MISSING);
    }

    @Test
    void directoryPolicyFileIsRejected() {
        assertPolicyRejects(() -> TestDatabasePolicyProvider.resolve("local", directory.toString()),
                Reason.POLICY_FILE_MISSING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc-url", "username", "database", "login-role", "effective-role", "owner-role"})
    void missingFieldIsRejected(String field) throws IOException {
        Map<String, String> fields = validFields();
        fields.remove(field);
        String file = writePolicy(fields).toString();
        assertPolicyRejects(() -> TestDatabasePolicyProvider.resolve("local", file), Reason.POLICY_FIELD_MISSING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"database", "owner-role"})
    void blankFieldIsRejected(String field) throws IOException {
        Map<String, String> fields = validFields();
        fields.put(field, " ");
        String file = writePolicy(fields).toString();
        assertPolicyRejects(() -> TestDatabasePolicyProvider.resolve("local", file), Reason.POLICY_FIELD_MISSING);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "jdbcUrl", "Database", "sql"})
    void unknownFieldIsRejected(String field) throws IOException {
        Map<String, String> fields = validFields();
        fields.put(field, "SyntheticSecretMarkerPw");
        String file = writePolicy(fields).toString();
        assertPolicyRejects(() -> TestDatabasePolicyProvider.resolve("local", file), Reason.POLICY_FIELD_UNKNOWN);
    }

    @Test
    void loginRoleDifferentFromUsernameIsRejected() throws IOException {
        Map<String, String> fields = validFields();
        fields.put("login-role", "synthetic_other");
        String file = writePolicy(fields).toString();
        assertPolicyRejects(() -> TestDatabasePolicyProvider.resolve("local", file), Reason.POLICY_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"synthetic_other", "synthetic_owner"})
    void effectiveRoleDifferentFromLoginRoleIsRejected(String effectiveRole) throws IOException {
        Map<String, String> fields = validFields();
        fields.put("effective-role", effectiveRole);
        String file = writePolicy(fields).toString();
        assertPolicyRejects(() -> TestDatabasePolicyProvider.resolve("local", file), Reason.POLICY_INVALID);
    }

    @Test
    void ownerRoleStaysIndependentOfLoginRole() throws IOException {
        Map<String, String> fields = validFields();
        fields.put("owner-role", "synthetic_separate_owner");
        TestDatabasePolicyProvider.Resolved resolved =
                TestDatabasePolicyProvider.resolve("local", writePolicy(fields).toString());
        assertThat(resolved.identity().ownerRole()).isEqualTo("synthetic_separate_owner");
        assertThat(resolved.identity().effectiveRole()).isEqualTo(USER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"synthetic_other", "Synthetic_db", "synthetic", "synthetic_db_", "synthetic_db "})
    void databaseDifferentFromPolicyUrlIsRejected(String database) throws IOException {
        Map<String, String> fields = validFields();
        fields.put("database", database);
        String file = writePolicy(fields).toString();
        assertPolicyRejects(() -> TestDatabasePolicyProvider.resolve("local", file), Reason.POLICY_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "jdbc:postgresql://synthetic-local.invalid/synthetic_db",
        "jdbc:postgresql://synthetic-local.invalid:015432/synthetic_db",
        "jdbc:postgresql://synthetic-local.invalid:15432/",
        "jdbc:postgresql://synthetic-local.invalid:15432/synthetic_db?sslmode=disable",
        "jdbc:postgresql://synthetic-local.invalid:15432/synthetic_db/extra",
        "jdbc:postgresql://synthetic-local.invalid,other.invalid:15432/synthetic_db",
        "jdbc:mysql://synthetic-local.invalid:15432/synthetic_db",
        "JDBC:postgresql://synthetic-local.invalid:15432/synthetic_db",
        "synthetic_db"})
    void policyUrlOutsideValidatorContractIsRejected(String url) throws IOException {
        Map<String, String> fields = validFields();
        fields.put("jdbc-url", url);
        String file = writePolicy(fields).toString();
        assertPolicyRejects(() -> TestDatabasePolicyProvider.resolve("local", file), Reason.POLICY_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"effective-role", "database", "jdbc-url"})
    void policyContractFailureStopsBeforeSettingsGuardOrSnapshot(String field) throws IOException {
        Map<String, String> fields = validFields();
        fields.put(field, switch (field) {
            case "effective-role" -> "synthetic_other";
            case "database" -> "synthetic_other";
            default -> "jdbc:postgresql://synthetic-local.invalid/synthetic_db";
        });
        GenericApplicationContext context = context(writePolicy(fields), Map.of());
        assertRejects(context, Stage.POLICY, Reason.POLICY_INVALID);
        assertNothingRegistered(context);
    }

    // ---- policy file syntax: duplicate keys ----

    @ParameterizedTest
    @ValueSource(strings = {
        "database=synthetic_db\n",
        "database=synthetic_changed\n",
        "database:synthetic_db\n",
        "d\\u0061tabase=synthetic_db\n",
        "owner\\-role=synthetic_owner\n",
        "jdbc-url = jdbc:postgresql://synthetic-local.invalid:15432/\\\n    synthetic_db\n"})
    void repeatedKeyIsRejected(String repeated) throws IOException {
        Path file = writeRawPolicy(rawValidPolicy() + repeated);
        TestDatabaseGuardException failure = assertPolicyRejects(
                () -> TestDatabasePolicyProvider.resolve("local", file.toString()), Reason.POLICY_FIELD_DUPLICATE);
        assertThat(failure.getMessage()).doesNotContain("database").doesNotContain("role")
                .doesNotContain("jdbc").doesNotContain(file.toString());
    }

    @Test
    void repeatedKeyIsRejectedBeforeSettingsGuardOrSnapshot() throws IOException {
        GenericApplicationContext context =
                context(writeRawPolicy(rawValidPolicy() + "username=" + USER + "\n"), Map.of());
        assertRejects(context, Stage.POLICY, Reason.POLICY_FIELD_DUPLICATE);
        assertNothingRegistered(context);
    }

    @Test
    void ordinaryPropertiesSyntaxIsStillParsed() throws IOException {
        Path file = writeRawPolicy("""
                # synthetic comment
                ! synthetic comment
                jdbc-url = jdbc:postgresql://synthetic-local.invalid:15432/\\
                    synthetic_db
                username:synthetic_login
                database   synthetic_db

                login-role=synthetic_login
                effective\\-role=synthetic_login
                owner-role=synthetic_\\u006fwner
                """);
        TestDatabasePolicyProvider.Resolved resolved = TestDatabasePolicyProvider.resolve("local", file.toString());
        assertThat(resolved.guardPolicy().targets().get(TestDatabaseMode.LOCAL).jdbcUrl()).isEqualTo(URL);
        assertThat(resolved.guardPolicy().targets().get(TestDatabaseMode.LOCAL).username()).isEqualTo(USER);
        assertThat(resolved.identity().database()).isEqualTo("synthetic_db");
        assertThat(resolved.identity().loginRole()).isEqualTo(USER);
        assertThat(resolved.identity().effectiveRole()).isEqualTo(USER);
        assertThat(resolved.identity().ownerRole()).isEqualTo("synthetic_owner");
    }

    @Test
    void rawValidPolicyFixtureIsAccepted() throws IOException {
        TestDatabasePolicyProvider.Resolved resolved =
                TestDatabasePolicyProvider.resolve("local", writeRawPolicy(rawValidPolicy()).toString());
        assertThat(resolved.identity().database()).isEqualTo("synthetic_db");
    }

    // ---- guarded initializer: settings supplied and checked before refresh ----

    @Test
    void validLocalSettingsSupplyFixedTimeoutsAndRegisterGuard() throws IOException {
        GenericApplicationContext context = context(writePolicy(validFields()), Map.of());
        new GuardedTestDatabaseInitializer().initialize(context);
        MutablePropertySources sources = context.getEnvironment().getPropertySources();
        assertThat(sources.iterator().next().getName()).isEqualTo(GuardedTestDatabaseInitializer.TIMEOUT_SOURCE_NAME);
        assertThat(context.getEnvironment().getProperty("spring.datasource.hikari.connection-timeout"))
                .isEqualTo("10000");
        assertThat(context.getBeanFactoryPostProcessors())
                .hasSize(1).first().isInstanceOf(TestDatabaseBeanDefinitionGuard.class);
        assertThat(context.getBeanFactory().containsSingleton(GuardedTestDatabaseInitializer.POLICY_SNAPSHOT_NAME))
                .isTrue();
        assertThat(GuardedTestDatabaseInitializer.policySnapshot(context.getBeanFactory()).mode())
                .isEqualTo(TestDatabaseMode.LOCAL);
    }

    @Test
    void datasourceTargetDifferentFromPolicyIsRejected() throws IOException {
        GenericApplicationContext context = context(writePolicy(validFields()),
                Map.of("spring.datasource.url", "jdbc:postgresql://synthetic-local.invalid:15432/other_db"));
        assertRejects(context, Stage.INITIALIZER, Reason.TARGET_MISMATCH);
        assertThat(context.getBeanFactory().containsSingleton(GuardedTestDatabaseInitializer.POLICY_SNAPSHOT_NAME))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "spring.datasource.hikari.data-source-properties.connectTimeout=30",
        "spring.datasource.hikari.data-source-properties.loginTimeout=0",
        "spring.datasource.hikari.data-source-properties.socketTimeout=0",
        "spring.datasource.hikari.data-source-properties.cancelSignalTimeout=10",
        "spring.datasource.hikari.connection-timeout=30000"})
    void conflictingTimeoutInAnySourceIsRejected(String setting) throws IOException {
        String[] pair = setting.split("=", 2);
        // Lower precedence than the supplied fixed values: shadowed conflicts are still rejected.
        GenericApplicationContext context = context(writePolicy(validFields()), Map.of(pair[0], pair[1]));
        assertRejects(context, Stage.INITIALIZER, Reason.DRIVER_TIMEOUT_REJECTED);
    }

    @Test
    void matchingTimeoutInAnotherSourceIsAccepted() throws IOException {
        GenericApplicationContext context = context(writePolicy(validFields()),
                Map.of("spring.datasource.hikari.data-source-properties.socketTimeout", "15"));
        new GuardedTestDatabaseInitializer().initialize(context);
        assertThat(context.getBeanFactoryPostProcessors()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "spring.datasource.hikari.data-source-properties.sslmode",
        "spring.datasource.hikari.data-source-properties.options",
        "spring.datasource.hikari.data-source-properties.connecttimeout",
        "spring.datasource.hikari.data-source-properties.connect-timeout",
        "spring.datasource.hikari.data-source-properties",
        "spring.datasource.hikari.data-source-class-name"})
    void unknownDriverPropertyIsRejected(String name) throws IOException {
        GenericApplicationContext context = context(writePolicy(validFields()), Map.of(name, "5"));
        assertRejects(context, Stage.INITIALIZER, Reason.ALTERNATE_SETTING_REJECTED);
    }

    @Test
    void guardedDelegateRequiresSuppliedTimeouts() throws IOException {
        TestDatabasePolicyProvider.Resolved resolved =
                TestDatabasePolicyProvider.resolve("local", writePolicy(validFields()).toString());
        GenericApplicationContext context = context(writePolicy(validFields()), Map.of());
        TestDatabaseGuardException failure = catchThrowableOfType(TestDatabaseGuardException.class,
                () -> TestDatabaseGuardInitializer.guarded(resolved.guardPolicy()).initialize(context));
        assertThat(failure.reason()).isEqualTo(Reason.DRIVER_TIMEOUT_MISSING);
    }

    @Test
    void policyFailureStopsBeforeSettingsAreSupplied() {
        GenericApplicationContext context = context(directory.resolve("absent.properties"), Map.of());
        assertRejects(context, Stage.POLICY, Reason.POLICY_FILE_MISSING);
        assertThat(context.getEnvironment().getPropertySources()
                .contains(GuardedTestDatabaseInitializer.TIMEOUT_SOURCE_NAME)).isFalse();
        assertThat(context.getBeanFactoryPostProcessors()).isEmpty();
        assertThat(context.getBeanFactory().containsSingleton(GuardedTestDatabaseInitializer.POLICY_SNAPSHOT_NAME))
                .isFalse();
    }

    // ---- policy snapshot: resolved once per context, read by the controlled configuration ----

    @Test
    void strategyUsesSnapshotAfterPolicyFileIsDeleted() throws IOException, SQLException {
        Path file = writePolicy(validFields());
        GenericApplicationContext context = context(file, Map.of());
        new GuardedTestDatabaseInitializer().initialize(context);
        TestDatabasePolicyProvider.Resolved snapshot =
                GuardedTestDatabaseInitializer.policySnapshot(context.getBeanFactory());
        Files.delete(file);

        GuardedFlywayMigrationStrategy strategy = strategy(context);
        assertThat(GuardedTestDatabaseInitializer.policySnapshot(context.getBeanFactory())).isSameAs(snapshot);
        assertMigrates(strategy, "synthetic_db", USER, "synthetic_owner");
    }

    @Test
    void strategyUsesSnapshotAfterPolicyFileAndModeChange() throws IOException, SQLException {
        Path file = writePolicy(validFields());
        GenericApplicationContext context = context(file, Map.of());
        new GuardedTestDatabaseInitializer().initialize(context);
        TestDatabasePolicyProvider.Resolved snapshot =
                GuardedTestDatabaseInitializer.policySnapshot(context.getBeanFactory());
        // Rewrite the file with another valid target and switch the mode; neither may be read again.
        writePolicy(file, otherFields());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("synthetic-changed",
                Map.of(TestDatabaseGuardInitializer.MODE_PROPERTY, "ci",
                        TestDatabasePolicyProvider.POLICY_FILE_PROPERTY, file.toString())));

        assertMigrates(strategy(context), "synthetic_db", USER, "synthetic_owner");
        assertIdentityRejects(strategy(context), "synthetic_other_db", "synthetic_other_login",
                "synthetic_other_owner", Reason.IDENTITY_DATABASE_MISMATCH);
        assertThat(GuardedTestDatabaseInitializer.policySnapshot(context.getBeanFactory())).isSameAs(snapshot);
        assertThat(snapshot.mode()).isEqualTo(TestDatabaseMode.LOCAL);
    }

    @Test
    void snapshotsOfDifferentContextsDoNotInterfere() throws IOException, SQLException {
        GenericApplicationContext first = context(writePolicy(validFields()), Map.of());
        GenericApplicationContext second = context(writePolicy(otherFields()), Map.of(
                "spring.datasource.url", OTHER_URL, "spring.datasource.username", "synthetic_other_login"));
        new GuardedTestDatabaseInitializer().initialize(first);
        new GuardedTestDatabaseInitializer().initialize(second);

        TestDatabasePolicyProvider.Resolved firstSnapshot =
                GuardedTestDatabaseInitializer.policySnapshot(first.getBeanFactory());
        TestDatabasePolicyProvider.Resolved secondSnapshot =
                GuardedTestDatabaseInitializer.policySnapshot(second.getBeanFactory());
        assertThat(firstSnapshot).isNotSameAs(secondSnapshot);
        assertThat(firstSnapshot.identity().database()).isEqualTo("synthetic_db");
        assertThat(secondSnapshot.identity().database()).isEqualTo("synthetic_other_db");
        assertThat(firstSnapshot.guardPolicy().targets().get(TestDatabaseMode.LOCAL).jdbcUrl()).isEqualTo(URL);
        assertThat(secondSnapshot.guardPolicy().targets().get(TestDatabaseMode.LOCAL).jdbcUrl()).isEqualTo(OTHER_URL);

        assertMigrates(strategy(first), "synthetic_db", USER, "synthetic_owner");
        assertMigrates(strategy(second), "synthetic_other_db", "synthetic_other_login", "synthetic_other_owner");
        assertIdentityRejects(strategy(first), "synthetic_other_db", "synthetic_other_login", "synthetic_other_owner",
                Reason.IDENTITY_DATABASE_MISMATCH);
        assertIdentityRejects(strategy(second), "synthetic_db", USER, "synthetic_owner",
                Reason.IDENTITY_DATABASE_MISMATCH);
    }

    @Test
    void childContextDoesNotUseParentSnapshot() throws IOException {
        GenericApplicationContext parent = context(writePolicy(validFields()), Map.of());
        new GuardedTestDatabaseInitializer().initialize(parent);
        GenericApplicationContext child = new GenericApplicationContext();
        child.setParent(parent);
        assertPolicyRejects(() -> strategy(child), Reason.POLICY_SNAPSHOT_MISSING);
    }

    @Test
    void strategyWithoutSnapshotIsRejectedEvenWhenPolicyIsResolvable() throws IOException {
        // A valid mode and policy file are present, but the configuration never resolves them itself.
        GenericApplicationContext context = context(writePolicy(validFields()), Map.of());
        assertPolicyRejects(() -> strategy(context), Reason.POLICY_SNAPSHOT_MISSING);
    }

    @Test
    void secondInitializationOfSameContextIsRejected() throws IOException {
        GenericApplicationContext context = context(writePolicy(validFields()), Map.of());
        new GuardedTestDatabaseInitializer().initialize(context);
        TestDatabasePolicyProvider.Resolved snapshot =
                GuardedTestDatabaseInitializer.policySnapshot(context.getBeanFactory());
        assertRejects(context, Stage.POLICY, Reason.POLICY_SNAPSHOT_DUPLICATE);
        assertThat(GuardedTestDatabaseInitializer.policySnapshot(context.getBeanFactory())).isSameAs(snapshot);
        assertThat(context.getBeanFactoryPostProcessors()).hasSize(1);
    }

    @Test
    void preOccupiedSnapshotNameIsRejectedBeforeSettingsAreSupplied() throws IOException {
        GenericApplicationContext context = context(writePolicy(validFields()), Map.of());
        context.getBeanFactory().registerSingleton(GuardedTestDatabaseInitializer.POLICY_SNAPSHOT_NAME,
                TestDatabasePolicyProvider.resolve("ci", null));
        assertRejects(context, Stage.POLICY, Reason.POLICY_SNAPSHOT_DUPLICATE);
        assertThat(context.getEnvironment().getPropertySources()
                .contains(GuardedTestDatabaseInitializer.TIMEOUT_SOURCE_NAME)).isFalse();
        assertThat(context.getBeanFactoryPostProcessors()).isEmpty();
    }

    @Test
    void snapshotNameDefinitionIsRejected() throws IOException {
        GenericApplicationContext context = context(writePolicy(validFields()), Map.of());
        context.registerBeanDefinition(GuardedTestDatabaseInitializer.POLICY_SNAPSHOT_NAME,
                new RootBeanDefinition(String.class));
        assertRejects(context, Stage.POLICY, Reason.POLICY_SNAPSHOT_DUPLICATE);
        assertPolicyRejects(() -> strategy(context), Reason.POLICY_SNAPSHOT_DUPLICATE);
    }

    @Test
    void additionalSnapshotUnderAnotherNameIsRejected() throws IOException {
        GenericApplicationContext context = context(writePolicy(validFields()), Map.of());
        new GuardedTestDatabaseInitializer().initialize(context);
        context.getBeanFactory().registerSingleton("syntheticExtraSnapshot",
                TestDatabasePolicyProvider.resolve("ci", null));
        assertPolicyRejects(() -> strategy(context), Reason.POLICY_SNAPSHOT_DUPLICATE);
    }

    @Test
    void snapshotUnderAnotherNameOnlyIsRejected() throws IOException {
        GenericApplicationContext context = context(writePolicy(validFields()), Map.of());
        context.getBeanFactory().registerSingleton("syntheticExtraSnapshot",
                TestDatabasePolicyProvider.resolve("ci", null));
        assertPolicyRejects(() -> strategy(context), Reason.POLICY_SNAPSHOT_DUPLICATE);
    }

    @Test
    void snapshotNameHoldingAnotherTypeIsRejected() throws IOException {
        GenericApplicationContext context = context(writePolicy(validFields()), Map.of());
        context.getBeanFactory().registerSingleton(GuardedTestDatabaseInitializer.POLICY_SNAPSHOT_NAME,
                "synthetic-impostor");
        assertPolicyRejects(() -> strategy(context), Reason.POLICY_SNAPSHOT_DUPLICATE);
    }

    @Test
    void snapshotRepresentationIsRedacted() throws IOException {
        GenericApplicationContext context = context(writePolicy(validFields()), Map.of());
        new GuardedTestDatabaseInitializer().initialize(context);
        assertThat(GuardedTestDatabaseInitializer.policySnapshot(context.getBeanFactory()).toString())
                .doesNotContain("synthetic");
    }

    // ---- helpers ----

    @GuardedTestDatabase
    static class Annotated {
    }

    private Map<String, String> validFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put("jdbc-url", URL);
        fields.put("username", USER);
        fields.put("database", "synthetic_db");
        fields.put("login-role", USER);
        fields.put("effective-role", USER);
        fields.put("owner-role", "synthetic_owner");
        return fields;
    }

    private static Map<String, String> otherFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put("jdbc-url", OTHER_URL);
        fields.put("username", "synthetic_other_login");
        fields.put("database", "synthetic_other_db");
        fields.put("login-role", "synthetic_other_login");
        fields.put("effective-role", "synthetic_other_login");
        fields.put("owner-role", "synthetic_other_owner");
        return fields;
    }

    /** The valid policy in plain Properties syntax, one key per line. */
    private static String rawValidPolicy() {
        return "jdbc-url=" + URL + "\n"
                + "username=" + USER + "\n"
                + "database=synthetic_db\n"
                + "login-role=" + USER + "\n"
                + "effective-role=" + USER + "\n"
                + "owner-role=synthetic_owner\n";
    }

    private Path writePolicy(Map<String, String> fields) throws IOException {
        return writePolicy(Files.createTempFile(directory, "policy", ".properties"), fields);
    }

    private static Path writePolicy(Path file, Map<String, String> fields) throws IOException {
        Properties properties = new Properties();
        properties.putAll(fields);
        try (var out = Files.newOutputStream(file)) {
            properties.store(out, null);
        }
        return file;
    }

    private Path writeRawPolicy(String content) throws IOException {
        Path file = Files.createTempFile(directory, "policy", ".properties");
        // Properties.load(InputStream) reads ISO 8859-1.
        Files.writeString(file, content, StandardCharsets.ISO_8859_1);
        return file;
    }

    private static GuardedFlywayMigrationStrategy strategy(GenericApplicationContext context) {
        return new GuardedTestDatabaseConfiguration().guardedFlywayMigrationStrategy(context.getBeanFactory());
    }

    /** Flyway double whose DataSource reports one identity row; nothing connects. */
    private static Flyway identityFlyway(String database, String role, String owner) throws SQLException {
        Flyway flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("database_name")).thenReturn(database);
        when(resultSet.getString("login_role")).thenReturn(role);
        when(resultSet.getString("effective_role")).thenReturn(role);
        when(resultSet.getString("database_owner")).thenReturn(owner);
        when(resultSet.getBoolean("rolcanlogin")).thenReturn(true);
        when(resultSet.wasNull()).thenReturn(false);
        return flyway;
    }

    private static void assertMigrates(GuardedFlywayMigrationStrategy strategy, String database, String role,
            String owner) throws SQLException {
        Flyway flyway = identityFlyway(database, role, owner);
        strategy.migrate(flyway);
        verify(flyway).migrate();
    }

    private static void assertIdentityRejects(GuardedFlywayMigrationStrategy strategy, String database, String role,
            String owner, Reason reason) throws SQLException {
        Flyway flyway = identityFlyway(database, role, owner);
        TestDatabaseGuardException failure =
                catchThrowableOfType(TestDatabaseGuardException.class, () -> strategy.migrate(flyway));
        assertThat(failure).isNotNull();
        assertThat(failure.stage()).isEqualTo(Stage.IDENTITY);
        assertThat(failure.reason()).isEqualTo(reason);
        verify(flyway, never()).migrate();
    }

    private static void assertNothingRegistered(GenericApplicationContext context) {
        assertThat(context.getEnvironment().getPropertySources()
                .contains(GuardedTestDatabaseInitializer.TIMEOUT_SOURCE_NAME)).isFalse();
        assertThat(context.getBeanFactoryPostProcessors()).isEmpty();
        assertThat(context.getBeanFactory().containsSingleton(GuardedTestDatabaseInitializer.POLICY_SNAPSHOT_NAME))
                .isFalse();
    }

    private static GenericApplicationContext context(Path policyFile, Map<String, Object> extra) {
        GenericApplicationContext context = new GenericApplicationContext();
        MutablePropertySources sources = context.getEnvironment().getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        Map<String, Object> settings = new HashMap<>();
        settings.put(TestDatabaseGuardInitializer.MODE_PROPERTY, "local");
        settings.put(TestDatabasePolicyProvider.POLICY_FILE_PROPERTY, policyFile.toString());
        settings.put("spring.datasource.url", URL);
        settings.put("spring.datasource.username", USER);
        settings.put("spring.datasource.password", PASSWORD);
        settings.putAll(extra);
        sources.addLast(new MapPropertySource("synthetic", settings));
        return context;
    }

    private static void assertRejects(GenericApplicationContext context, Stage stage, Reason reason) {
        TestDatabaseGuardException failure = catchThrowableOfType(TestDatabaseGuardException.class,
                () -> new GuardedTestDatabaseInitializer().initialize(context));
        assertThat(failure).isNotNull();
        assertThat(failure.stage()).isEqualTo(stage);
        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getMessage()).doesNotContain(PASSWORD).doesNotContain("synthetic");
    }

    private static TestDatabaseGuardException assertPolicyRejects(Runnable action, Reason reason) {
        TestDatabaseGuardException failure = catchThrowableOfType(TestDatabaseGuardException.class, action::run);
        assertThat(failure).isNotNull();
        assertThat(failure.stage()).isEqualTo(Stage.POLICY);
        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getMessage()).doesNotContain("Synthetic").doesNotContain("synthetic");
        return failure;
    }
}
