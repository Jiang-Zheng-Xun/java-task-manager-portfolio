package io.github.jiangzhengxun.taskmanager.support.testdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Synthetic Flyway and JDBC doubles only; no migration or database is run. */
class GuardedFlywayMigrationStrategyTest {

    private static final TestDatabaseIdentityPolicy POLICY =
            new TestDatabaseIdentityPolicy("synthetic_db", "synthetic_login", "synthetic_login", "synthetic_login");

    private Flyway flyway;
    private DataSource dataSource;
    private Connection connection;
    private Statement statement;
    private ResultSet resultSet;

    @BeforeEach
    void setUp() throws SQLException {
        flyway = mock(Flyway.class);
        Configuration configuration = mock(Configuration.class);
        dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        resultSet = mock(ResultSet.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getDataSource()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("database_name")).thenReturn("synthetic_db");
        when(resultSet.getString("login_role")).thenReturn("synthetic_login");
        when(resultSet.getString("effective_role")).thenReturn("synthetic_login");
        when(resultSet.getString("database_owner")).thenReturn("synthetic_login");
        when(resultSet.getBoolean("rolcanlogin")).thenReturn(true);
        when(resultSet.wasNull()).thenReturn(false);
    }

    private GuardedFlywayMigrationStrategy strategy() {
        return new GuardedFlywayMigrationStrategy(POLICY);
    }

    @Test
    void validIdentityMigratesOnlyAfterCleanupThroughFlywayDataSource() throws SQLException {
        strategy().migrate(flyway);

        InOrder order = inOrder(dataSource, resultSet, statement, connection, flyway);
        order.verify(dataSource).getConnection();
        order.verify(resultSet).close();
        order.verify(statement).close();
        order.verify(connection).rollback();
        order.verify(connection).close();
        order.verify(flyway).migrate();
    }

    @Test
    void identityMismatchDoesNotMigrate() throws SQLException {
        when(resultSet.getBoolean("rolsuper")).thenReturn(true);
        assertRejectedWithoutMigration(Reason.IDENTITY_PRIVILEGE_REJECTED);
    }

    @Test
    void queryFailureDoesNotMigrate() throws SQLException {
        when(statement.executeQuery(anyString())).thenThrow(new SQLException("synthetic-secret-detail"));
        assertRejectedWithoutMigration(Reason.IDENTITY_QUERY_FAILED);
    }

    @Test
    void cleanupFailureDoesNotMigrate() throws SQLException {
        doThrow(new SQLException("synthetic-secret-detail")).when(connection).rollback();
        assertRejectedWithoutMigration(Reason.IDENTITY_CLEANUP_FAILED);
    }

    @Test
    void missingRowDoesNotMigrate() throws SQLException {
        when(resultSet.next()).thenReturn(false);
        assertRejectedWithoutMigration(Reason.IDENTITY_ROW_MISSING);
    }

    @Test
    void unavailableFlywayDataSourceDoesNotMigrate() {
        when(flyway.getConfiguration()).thenThrow(new IllegalStateException("synthetic-secret-detail"));
        assertRejectedWithoutMigration(Reason.IDENTITY_DATASOURCE_UNAVAILABLE);
    }

    @Test
    void nullFlywayDataSourceDoesNotMigrate() {
        when(flyway.getConfiguration().getDataSource()).thenReturn(null);
        assertRejectedWithoutMigration(Reason.IDENTITY_DATASOURCE_UNAVAILABLE);
    }

    @Test
    void successRecordsOrderedTimelineWithExecutedCount() {
        MigrateResult result = mock(MigrateResult.class);
        result.migrationsExecuted = 3;
        when(flyway.migrate()).thenReturn(result);
        GuardedFlywayMigrationStrategy strategy = strategy();
        strategy.migrate(flyway);
        List<GuardedFlywayMigrationStrategy.Timeline.Event> events = strategy.timeline().events();
        assertThat(events).extracting(GuardedFlywayMigrationStrategy.Timeline.Event::kind).containsExactly(
                GuardedFlywayMigrationStrategy.Timeline.IDENTITY_VERIFIED_AND_RELEASED,
                GuardedFlywayMigrationStrategy.Timeline.MIGRATE_INVOKED,
                GuardedFlywayMigrationStrategy.Timeline.MIGRATE_RETURNED);
        assertThat(events).extracting(GuardedFlywayMigrationStrategy.Timeline.Event::sequence).containsExactly(1L, 2L, 3L);
        assertThat(events.get(2).count()).isEqualTo(3);
        assertThat(events).extracting(GuardedFlywayMigrationStrategy.Timeline.Event::thread)
                .containsOnly(Thread.currentThread().getName());
    }

    @Test
    void verificationFailureRecordsNoEventAndDoesNotMigrate() throws SQLException {
        when(resultSet.getBoolean("rolsuper")).thenReturn(true);
        GuardedFlywayMigrationStrategy strategy = strategy();
        TestDatabaseGuardException failure =
                catchThrowableOfType(TestDatabaseGuardException.class, () -> strategy.migrate(flyway));
        assertThat(failure.reason()).isEqualTo(Reason.IDENTITY_PRIVILEGE_REJECTED);
        assertThat(strategy.timeline().events()).isEmpty();
        verify(flyway, never()).migrate();
    }

    @Test
    void migrationFailureRecordsInvocationButNoReturn() {
        IllegalStateException migrationFailure = new IllegalStateException("synthetic-migration-failure");
        when(flyway.migrate()).thenThrow(migrationFailure);
        GuardedFlywayMigrationStrategy strategy = strategy();
        IllegalStateException thrown = catchThrowableOfType(IllegalStateException.class, () -> strategy.migrate(flyway));
        assertThat(thrown).isSameAs(migrationFailure);
        assertThat(strategy.timeline().events()).extracting(GuardedFlywayMigrationStrategy.Timeline.Event::kind)
                .containsExactly(GuardedFlywayMigrationStrategy.Timeline.IDENTITY_VERIFIED_AND_RELEASED,
                        GuardedFlywayMigrationStrategy.Timeline.MIGRATE_INVOKED);
    }

    @Test
    void representationIsRedacted() {
        assertThat(strategy().toString()).doesNotContain("synthetic_");
        assertThat(POLICY.toString()).doesNotContain("synthetic_");
    }

    private void assertRejectedWithoutMigration(Reason reason) {
        TestDatabaseGuardException failure =
                catchThrowableOfType(TestDatabaseGuardException.class, () -> strategy().migrate(flyway));
        assertThat(failure).isNotNull();
        assertThat(failure.reason()).isEqualTo(reason);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getSuppressed()).isEmpty();
        assertThat(failure.getMessage()).doesNotContain("synthetic");
        verify(flyway, never()).migrate();
    }
}
