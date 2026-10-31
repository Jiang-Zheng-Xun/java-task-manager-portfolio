package io.github.jiangzhengxun.taskmanager.support.testdb;

import java.util.Objects;

/**
 * Fixed-diagnostic rejection raised by the test database guard.
 *
 * <p>The message only names the guard stage and a fixed reason code. It never carries a cause, raw URL, username,
 * password or property value.
 */
public final class TestDatabaseGuardException extends IllegalStateException {

    /** Lifecycle stage that rejected the context. */
    public enum Stage {
        POLICY,
        INITIALIZER,
        BEAN_DEFINITION,
        IDENTITY
    }

    /** Fixed rejection reasons. The first six mirror {@link TestDatabaseConfigValidator.ErrorCategory}. */
    public enum Reason {
        MODE_REJECTED,
        POLICY_MISSING,
        POLICY_INVALID,
        CONFIG_MISSING,
        URL_UNSUPPORTED,
        TARGET_MISMATCH,
        SETTINGS_UNRESOLVABLE,
        ALTERNATE_SETTING_REJECTED,
        PROPERTY_SOURCE_UNVERIFIABLE,
        PROPERTY_SOURCE_INSPECTION_FAILED,
        FLYWAY_DISABLED,
        DEFINITION_INSPECTION_FAILED,
        DEFINITION_UNVERIFIABLE,
        FACTORY_BEAN_REJECTED,
        DATASOURCE_UNSUPPORTED,
        CONNECTION_DETAILS_UNSUPPORTED,
        FLYWAY_UNSUPPORTED,
        FLYWAY_CONNECTION_DETAILS_UNSUPPORTED,
        MIGRATION_INITIALIZER_UNSUPPORTED,
        MIGRATION_STRATEGY_REJECTED,
        FLYWAY_CUSTOMIZER_REJECTED,
        FLYWAY_EXTENSION_REJECTED,
        POLICY_FILE_MISSING,
        POLICY_FILE_UNREADABLE,
        POLICY_FIELD_MISSING,
        POLICY_FIELD_UNKNOWN,
        POLICY_FIELD_DUPLICATE,
        POLICY_SNAPSHOT_MISSING,
        POLICY_SNAPSHOT_DUPLICATE,
        DRIVER_TIMEOUT_REJECTED,
        DRIVER_TIMEOUT_MISSING,
        IDENTITY_DATASOURCE_UNAVAILABLE,
        IDENTITY_QUERY_FAILED,
        IDENTITY_ROW_MISSING,
        IDENTITY_ROW_DUPLICATE,
        IDENTITY_VALUE_NULL,
        IDENTITY_DATABASE_MISMATCH,
        IDENTITY_ROLE_MISMATCH,
        IDENTITY_OWNER_MISMATCH,
        IDENTITY_PRIVILEGE_REJECTED,
        IDENTITY_MEMBERSHIP_REJECTED,
        IDENTITY_CLEANUP_FAILED
    }

    private final Stage stage;
    private final Reason reason;

    TestDatabaseGuardException(Stage stage, Reason reason) {
        super("TEST_DB_GUARD " + Objects.requireNonNull(stage, "stage").name() + ": "
                + Objects.requireNonNull(reason, "reason").name());
        this.stage = stage;
        this.reason = reason;
    }

    public Stage stage() {
        return stage;
    }

    public Reason reason() {
        return reason;
    }
}
