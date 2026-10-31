package io.github.jiangzhengxun.taskmanager.support.testdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

/** Synthetic JDBC doubles only; no database is contacted. */
class TestDatabaseIdentityVerifierTest {

    private static final String SECRET = "synthetic-secret-detail";
    private static final TestDatabaseIdentityPolicy POLICY =
            new TestDatabaseIdentityPolicy("synthetic_db", "synthetic_login", "synthetic_login", "synthetic_login");

    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private ResultSet resultSet;
    private Map<String, Object> row;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        row = new HashMap<>();
        row.put("database_name", "synthetic_db");
        row.put("login_role", "synthetic_login");
        row.put("effective_role", "synthetic_login");
        row.put("database_owner", "synthetic_login");
        row.put("rolcanlogin", true);
        row.put("rolsuper", false);
        row.put("rolcreatedb", false);
        row.put("rolcreaterole", false);
        row.put("rolreplication", false);
        row.put("rolbypassrls", false);
        row.put("has_role_membership", false);
        stubRow();
    }

    private void stubRow() throws SQLException {
        Object[] last = new Object[1];
        when(resultSet.getString(anyString())).thenAnswer(call -> {
            last[0] = row.get(call.<String>getArgument(0));
            return last[0];
        });
        when(resultSet.getBoolean(anyString())).thenAnswer(call -> {
            last[0] = row.get(call.<String>getArgument(0));
            return Boolean.TRUE.equals(last[0]);
        });
        when(resultSet.wasNull()).thenAnswer(call -> last[0] == null);
    }

    private Reason failureReason() {
        TestDatabaseGuardException failure = catchThrowableOfType(TestDatabaseGuardException.class,
                () -> new TestDatabaseIdentityVerifier().verify(dataSource, POLICY));
        assertThat(failure).isNotNull();
        assertThat(failure.stage()).isEqualTo(Stage.IDENTITY);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getMessage()).doesNotContain(SECRET).doesNotContain("synthetic_");
        return failure.reason();
    }

    @Test
    void matchingIdentityUsesFixedQueryReadOnlyTransactionAndReleasesInOrder() throws SQLException {
        assertThatCode(() -> new TestDatabaseIdentityVerifier().verify(dataSource, POLICY)).doesNotThrowAnyException();

        InOrder order = inOrder(connection, statement, resultSet);
        order.verify(connection).setAutoCommit(false);
        order.verify(connection).setReadOnly(true);
        order.verify(statement).setQueryTimeout(5);
        order.verify(statement).executeQuery(TestDatabaseIdentityVerifier.IDENTITY_SQL);
        order.verify(resultSet).close();
        order.verify(statement).close();
        order.verify(connection).rollback();
        order.verify(connection).close();
        verify(connection, never()).commit();
    }

    @Test
    void fixedQueryReadsLoginRoleCapabilitiesAndMembership() {
        assertThat(TestDatabaseIdentityVerifier.IDENTITY_SQL)
                .contains("session_user AS login_role", "current_user AS effective_role",
                        "owner_role.oid = database_info.datdba", "login_role.rolname = session_user",
                        "pg_catalog.pg_auth_members membership", "membership.member = login_role.oid",
                        "WHERE database_info.datname = current_database()");
    }

    @Test
    void databaseMismatchIsRejected() {
        row.put("database_name", "other_db");
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_DATABASE_MISMATCH);
    }

    @ParameterizedTest
    @ValueSource(strings = {"login_role", "effective_role"})
    void roleMismatchIsRejected(String column) {
        row.put(column, "other_role");
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_ROLE_MISMATCH);
    }

    @Test
    void ownerMismatchIsRejected() {
        row.put("database_owner", "other_owner");
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_OWNER_MISMATCH);
    }

    @Test
    void loginDisabledIsRejected() {
        row.put("rolcanlogin", false);
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_PRIVILEGE_REJECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"rolsuper", "rolcreatedb", "rolcreaterole", "rolreplication", "rolbypassrls"})
    void elevatedCapabilityIsRejected(String column) {
        row.put(column, true);
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_PRIVILEGE_REJECTED);
    }

    @Test
    void roleMembershipIsRejected() {
        row.put("has_role_membership", true);
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_MEMBERSHIP_REJECTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"database_name", "login_role", "effective_role", "database_owner", "rolcanlogin",
            "rolsuper", "rolcreatedb", "rolcreaterole", "rolreplication", "rolbypassrls", "has_role_membership"})
    void nullValueIsRejected(String column) {
        row.put(column, null);
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_VALUE_NULL);
    }

    @Test
    void missingRowIsRejected() throws SQLException {
        when(resultSet.next()).thenReturn(false);
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_ROW_MISSING);
        verify(connection).rollback();
        verify(connection).close();
    }

    @Test
    void duplicateRowIsRejected() throws SQLException {
        when(resultSet.next()).thenReturn(true, true, false);
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_ROW_DUPLICATE);
    }

    @Test
    void connectionFailureIsMaskedAndNothingElseIsTouched() throws SQLException {
        when(dataSource.getConnection()).thenThrow(new SQLException(SECRET));
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_QUERY_FAILED);
    }

    @Test
    void queryFailureIsMaskedAndStillReleasesResources() throws SQLException {
        SQLException failure = new SQLException(SECRET);
        failure.addSuppressed(new IllegalStateException(SECRET));
        when(statement.executeQuery(anyString())).thenThrow(failure);
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_QUERY_FAILED);
        verify(statement).close();
        verify(connection).rollback();
        verify(connection).close();
    }

    @Test
    void queryTimeoutSetupFailureIsRejected() throws SQLException {
        doThrow(new SQLException(SECRET)).when(statement).setQueryTimeout(5);
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_QUERY_FAILED);
        verify(statement, never()).executeQuery(anyString());
    }

    @Test
    void resultSetCloseFailureIsRejectedAfterRemainingCleanup() throws SQLException {
        doThrow(new SQLException(SECRET)).when(resultSet).close();
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_CLEANUP_FAILED);
        verify(statement).close();
        verify(connection).rollback();
        verify(connection).close();
    }

    @Test
    void statementCloseFailureIsRejected() throws SQLException {
        doThrow(new SQLException(SECRET)).when(statement).close();
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_CLEANUP_FAILED);
        verify(connection).close();
    }

    @Test
    void rollbackFailureIsRejected() throws SQLException {
        doThrow(new SQLException(SECRET)).when(connection).rollback();
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_CLEANUP_FAILED);
        verify(connection).close();
    }

    @Test
    void connectionCloseFailureIsRejected() throws SQLException {
        doThrow(new SQLException(SECRET)).when(connection).close();
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_CLEANUP_FAILED);
    }

    @Test
    void identityMismatchTakesPrecedenceOnlyAfterCleanupSucceeded() throws SQLException {
        row.put("rolsuper", true);
        doThrow(new SQLException(SECRET)).when(connection).close();
        assertThat(failureReason()).isEqualTo(Reason.IDENTITY_CLEANUP_FAILED);
    }

    @Test
    void missingDataSourceIsRejected() {
        TestDatabaseGuardException failure = catchThrowableOfType(TestDatabaseGuardException.class,
                () -> new TestDatabaseIdentityVerifier().verify(null, POLICY));
        assertThat(failure.reason()).isEqualTo(Reason.IDENTITY_DATASOURCE_UNAVAILABLE);
    }
}
