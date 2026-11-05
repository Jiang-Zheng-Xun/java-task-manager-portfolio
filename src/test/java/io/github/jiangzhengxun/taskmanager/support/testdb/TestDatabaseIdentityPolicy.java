package io.github.jiangzhengxun.taskmanager.support.testdb;

import java.util.Objects;

/**
 * Expected database identity verified before migration, independent of the datasource settings under test.
 *
 * <p>Besides the four names below, every policy requires the login role to be able to log in, to hold none of
 * superuser, create-database, create-role, replication or bypass-RLS, and to have no {@code pg_auth_members}
 * membership. The policy holds no password. Its string representation is redacted.
 */
public final class TestDatabaseIdentityPolicy {

    private final String database;
    private final String loginRole;
    private final String effectiveRole;
    private final String ownerRole;

    TestDatabaseIdentityPolicy(String database, String loginRole, String effectiveRole, String ownerRole) {
        this.database = Objects.requireNonNull(database, "database");
        this.loginRole = Objects.requireNonNull(loginRole, "loginRole");
        this.effectiveRole = Objects.requireNonNull(effectiveRole, "effectiveRole");
        this.ownerRole = Objects.requireNonNull(ownerRole, "ownerRole");
    }

    String database() {
        return database;
    }

    String loginRole() {
        return loginRole;
    }

    String effectiveRole() {
        return effectiveRole;
    }

    String ownerRole() {
        return ownerRole;
    }

    @Override
    public String toString() {
        return "TestDatabaseIdentityPolicy[redacted]";
    }
}
