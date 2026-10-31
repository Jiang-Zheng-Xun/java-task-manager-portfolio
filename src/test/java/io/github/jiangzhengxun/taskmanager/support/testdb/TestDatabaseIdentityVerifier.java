package io.github.jiangzhengxun.taskmanager.support.testdb;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Runs one fixed identity query in a read-only transaction and compares the single row with an independent
 * {@link TestDatabaseIdentityPolicy}.
 *
 * <p>No external SQL is accepted. The result set, statement, transaction and connection are always released, in that
 * order; verification succeeds only when the row matches and every cleanup step succeeded. External messages, causes and
 * suppressed exceptions are discarded and replaced by fixed diagnostics.
 */
public final class TestDatabaseIdentityVerifier {

    static final int QUERY_TIMEOUT_SECONDS = 5;

    static final String IDENTITY_SQL = """
            SELECT
                current_database() AS database_name,
                session_user AS login_role,
                current_user AS effective_role,
                owner_role.rolname AS database_owner,
                login_role.rolcanlogin,
                login_role.rolsuper,
                login_role.rolcreatedb,
                login_role.rolcreaterole,
                login_role.rolreplication,
                login_role.rolbypassrls,
                EXISTS (
                    SELECT 1
                    FROM pg_catalog.pg_auth_members membership
                    WHERE membership.member = login_role.oid
                ) AS has_role_membership
            FROM pg_catalog.pg_database database_info
            JOIN pg_catalog.pg_roles owner_role
                ON owner_role.oid = database_info.datdba
            JOIN pg_catalog.pg_roles login_role
                ON login_role.rolname = session_user
            WHERE database_info.datname = current_database()
            """;

    private record Observed(String database, String loginRole, String effectiveRole, String owner, boolean canLogin,
            boolean superuser, boolean createDb, boolean createRole, boolean replication, boolean bypassRls,
            boolean membership) {
    }

    /** Returns normally only when the identity matches and all JDBC resources were released successfully. */
    public void verify(DataSource dataSource, TestDatabaseIdentityPolicy policy) {
        if (dataSource == null || policy == null) {
            throw reject(Reason.IDENTITY_DATASOURCE_UNAVAILABLE);
        }
        Reason failure = null;
        Observed observed = null;
        Connection connection = null;
        Statement statement = null;
        ResultSet resultSet = null;
        boolean transactionOpen = false;
        try {
            connection = dataSource.getConnection();
            if (connection == null) {
                throw reject(Reason.IDENTITY_DATASOURCE_UNAVAILABLE);
            }
            connection.setAutoCommit(false);
            transactionOpen = true;
            connection.setReadOnly(true);
            statement = connection.createStatement();
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            resultSet = statement.executeQuery(IDENTITY_SQL);
            observed = readSingleRow(resultSet);
        } catch (TestDatabaseGuardException rejection) {
            failure = rejection.reason();
        } catch (SQLException | RuntimeException queryFailure) {
            failure = Reason.IDENTITY_QUERY_FAILED;
        }
        // Non-short-circuit: every step is attempted even after an earlier failure.
        boolean released = close(resultSet) & close(statement) & rollback(connection, transactionOpen)
                & close(connection);
        if (failure != null) {
            throw reject(failure);
        }
        if (!released) {
            throw reject(Reason.IDENTITY_CLEANUP_FAILED);
        }
        compare(Objects.requireNonNull(observed), policy);
    }

    private static Observed readSingleRow(ResultSet resultSet) throws SQLException {
        if (resultSet == null || !resultSet.next()) {
            throw reject(Reason.IDENTITY_ROW_MISSING);
        }
        Observed observed = new Observed(
                text(resultSet, "database_name"), text(resultSet, "login_role"), text(resultSet, "effective_role"),
                text(resultSet, "database_owner"), flag(resultSet, "rolcanlogin"), flag(resultSet, "rolsuper"),
                flag(resultSet, "rolcreatedb"), flag(resultSet, "rolcreaterole"), flag(resultSet, "rolreplication"),
                flag(resultSet, "rolbypassrls"), flag(resultSet, "has_role_membership"));
        if (resultSet.next()) {
            throw reject(Reason.IDENTITY_ROW_DUPLICATE);
        }
        return observed;
    }

    private static String text(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        if (value == null || resultSet.wasNull()) {
            throw reject(Reason.IDENTITY_VALUE_NULL);
        }
        return value;
    }

    private static boolean flag(ResultSet resultSet, String column) throws SQLException {
        boolean value = resultSet.getBoolean(column);
        if (resultSet.wasNull()) {
            throw reject(Reason.IDENTITY_VALUE_NULL);
        }
        return value;
    }

    private static void compare(Observed observed, TestDatabaseIdentityPolicy policy) {
        if (!policy.database().equals(observed.database())) {
            throw reject(Reason.IDENTITY_DATABASE_MISMATCH);
        }
        if (!policy.loginRole().equals(observed.loginRole())
                || !policy.effectiveRole().equals(observed.effectiveRole())) {
            throw reject(Reason.IDENTITY_ROLE_MISMATCH);
        }
        if (!policy.ownerRole().equals(observed.owner())) {
            throw reject(Reason.IDENTITY_OWNER_MISMATCH);
        }
        if (!observed.canLogin() || observed.superuser() || observed.createDb() || observed.createRole()
                || observed.replication() || observed.bypassRls()) {
            throw reject(Reason.IDENTITY_PRIVILEGE_REJECTED);
        }
        if (observed.membership()) {
            throw reject(Reason.IDENTITY_MEMBERSHIP_REJECTED);
        }
    }

    private static boolean close(AutoCloseable resource) {
        if (resource == null) {
            return true;
        }
        try {
            resource.close();
            return true;
        } catch (Exception closeFailure) {
            return false;
        }
    }

    private static boolean rollback(Connection connection, boolean transactionOpen) {
        if (connection == null || !transactionOpen) {
            return true;
        }
        try {
            connection.rollback();
            return true;
        } catch (SQLException | RuntimeException rollbackFailure) {
            return false;
        }
    }

    private static TestDatabaseGuardException reject(Reason reason) {
        return new TestDatabaseGuardException(Stage.IDENTITY, reason);
    }
}
