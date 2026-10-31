package io.github.jiangzhengxun.taskmanager.support.testdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Real-JDBC identity rejection controls for {@link GuardedFlywayMigrationStrategy} with the actual
 * {@link TestDatabaseIdentityVerifier}. The property {@code -Dtaskmanager.guardedIdentityIntegration.control} selects one
 * control: absent disables every test; an exact supported value enables that control; any other value, including empty,
 * fails {@link #enabledRunHasKnownControl()} without resolving policy, reading credentials or connecting.
 *
 * <p>Scope: direct strategy calls only, not Spring bootstrap or JPA. The Flyway subclass exists only to detect a
 * forbidden {@code migrate()} entry and cannot establish real migration success.
 *
 * <p>Future execution must supply {@code TEST_DB_MODE=local}, {@code TEST_DB_POLICY_FILE} (the existing private policy),
 * and {@code spring.datasource.url}, {@code spring.datasource.username} and {@code spring.datasource.password}. The url and
 * username must equal the policy target exactly; they are compared before authentication is read or a connection is
 * obtained. Failures use fixed labels and never show supplied or expected values.
 */
class GuardedTestDatabaseIdentityIntegrationTest {

    private static final String CONTROL = "taskmanager.guardedIdentityIntegration.control";
    private static final List<String> CLEANUP_ORDER = List.of("RESULTSET_CLOSE", "STATEMENT_CLOSE", "ROLLBACK",
            "CONNECTION_CLOSE");

    /** Any present value must name a known control; an unknown or empty value fails here. */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = ".*")
    void enabledRunHasKnownControl() {
        String control = System.getProperty(CONTROL);
        if (!"ID_REJECT".equals(control) && !"CLEANUP_REJECT".equals(control)) {
            throw new AssertionError("CONTROL:UNKNOWN");
        }
    }

    /** The owner expectation differs in exactly one field; the datasource target and shared policy stay intact. */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "ID_REJECT")
    void controlIdReject_ownerMismatchRejectedBeforeMigration() throws SQLException {
        TestDatabasePolicyProvider.Resolved resolved = resolveLocalPolicy();
        TestDatabaseIdentityPolicy approved = resolved.identity();
        TestDatabaseIdentityPolicy differing = new TestDatabaseIdentityPolicy(approved.database(),
                approved.loginRole(), approved.effectiveRole(), approved.ownerRole() + "_not_owner");
        TrackingDataSource dataSource = new TrackingDataSource(realDataSource(resolved), false);
        runRejection(dataSource, differing, Reason.IDENTITY_OWNER_MISMATCH, 0);
    }

    /** Close really releases the connection, then reports one fixed synthetic failure. */
    @Test
    @EnabledIfSystemProperty(named = CONTROL, matches = "CLEANUP_REJECT")
    void controlCleanupReject_closeFailureRejectedBeforeMigration() throws SQLException {
        TestDatabasePolicyProvider.Resolved resolved = resolveLocalPolicy();
        TrackingDataSource dataSource = new TrackingDataSource(realDataSource(resolved), true);
        runRejection(dataSource, resolved.identity(), Reason.IDENTITY_CLEANUP_FAILED, 1);
    }

    private static void runRejection(TrackingDataSource dataSource, TestDatabaseIdentityPolicy policy, Reason reason,
            int injectedFailures) throws SQLException {
        MigrateForbiddenFlyway flyway = new MigrateForbiddenFlyway(dataSource);
        Throwable primary = null;
        try {
            Throwable thrown = catchThrowable(() -> new GuardedFlywayMigrationStrategy(policy).migrate(flyway));
            assertThat(thrown).isInstanceOf(TestDatabaseGuardException.class);
            TestDatabaseGuardException rejection = (TestDatabaseGuardException) thrown;
            assertThat(rejection.stage()).isEqualTo(Stage.IDENTITY);
            assertThat(rejection.reason()).isEqualTo(reason);
            assertThat(flyway.migrateEntries.get()).as("migrate entries").isZero();
            assertThat(dataSource.connections.get()).as("connections acquired").isEqualTo(1);
            assertThat(dataSource.events).as("cleanup order").containsExactlyElementsOf(CLEANUP_ORDER);
            assertThat(dataSource.injectedFailures.get()).as("injected failures").isEqualTo(injectedFailures);
            assertThat(dataSource.allUnderlyingClosed()).as("underlying resources released").isTrue();
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            releaseUnderlying(dataSource, primary);
        }
    }

    /** Resolves the private policy once through the real provider; only local mode is accepted. */
    private static TestDatabasePolicyProvider.Resolved resolveLocalPolicy() {
        TestDatabasePolicyProvider.Resolved resolved = TestDatabasePolicyProvider.resolve(new StandardEnvironment());
        if (resolved.mode() != TestDatabaseMode.LOCAL) {
            throw new AssertionError("TARGET:MODE_NOT_LOCAL");
        }
        return resolved;
    }

    /**
     * Compares the supplied url and username exactly with the policy target, then reads authentication. Built only
     * inside an enabled control, with the guard's driver timeouts.
     */
    private static DataSource realDataSource(TestDatabasePolicyProvider.Resolved resolved) {
        TestDatabaseConfigValidator.ExpectedTarget target = resolved.guardPolicy().targets().get(TestDatabaseMode.LOCAL);
        if (target == null) {
            throw new AssertionError("TARGET:POLICY_TARGET_MISSING");
        }
        String url = System.getProperty("spring.datasource.url");
        String user = System.getProperty("spring.datasource.username");
        if (url == null || url.isBlank()) {
            throw new AssertionError("TARGET:URL_MISSING");
        }
        if (user == null || user.isBlank()) {
            throw new AssertionError("TARGET:USERNAME_MISSING");
        }
        if (!target.jdbcUrl().equals(url)) {
            throw new AssertionError("TARGET:URL_MISMATCH");
        }
        if (!target.username().equals(user)) {
            throw new AssertionError("TARGET:USERNAME_MISMATCH");
        }
        // Authentication is read only after the target matched.
        // Environment-capable lookup (system properties, then SPRING_DATASOURCE_PASSWORD via relaxed environment names),
        // so the secret can be supplied through the child-process environment instead of the command line.
        String password = new StandardEnvironment().getProperty("spring.datasource.password");
        if (password == null || password.isEmpty()) {
            throw new AssertionError("AUTH:MISSING");
        }
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(url);
        dataSource.setUser(user);
        dataSource.setPassword(password);
        dataSource.setConnectTimeout(5);
        dataSource.setLoginTimeout(5);
        dataSource.setSocketTimeout(10);
        return dataSource;
    }

    /**
     * Fallback release of this control's underlying resources: result sets, then statements, then connections, each
     * kind in reverse acquisition order. Inspection and close are separate; an inspection failure still attempts close,
     * and every remaining resource is processed. Problems become fixed labels and are never discarded.
     */
    private static void releaseUnderlying(TrackingDataSource dataSource, Throwable primary) {
        List<String> problems = new ArrayList<>();
        for (String kind : List.of("RESULTSET", "STATEMENT", "CONNECTION")) {
            List<AutoCloseable> resources = dataSource.underlying.stream()
                    .filter(resource -> kind(resource).equals(kind)).toList();
            for (int i = resources.size() - 1; i >= 0; i--) {
                releaseOne(resources.get(i), kind, problems);
            }
        }
        if (problems.isEmpty()) {
            return;
        }
        if (primary != null) {
            problems.forEach(label -> primary.addSuppressed(new AssertionError(label)));
            return;
        }
        AssertionError failure = new AssertionError("CLEANUP:FAILED");
        problems.forEach(label -> failure.addSuppressed(new AssertionError(label)));
        throw failure;
    }

    private static void releaseOne(AutoCloseable resource, String kind, List<String> problems) {
        boolean open;
        try {
            open = !isClosed(resource);
        } catch (Exception inspectionFailure) {
            problems.add("CLEANUP:" + kind + "_INSPECT_FAILED");
            open = true;
        }
        if (!open) {
            return;
        }
        problems.add("CLEANUP:" + kind + "_LEFT_OPEN");
        try {
            resource.close();
        } catch (Exception closeFailure) {
            problems.add("CLEANUP:" + kind + "_CLOSE_FAILED");
        }
        try {
            if (!isClosed(resource)) {
                problems.add("CLEANUP:" + kind + "_STILL_OPEN");
            }
        } catch (Exception inspectionFailure) {
            problems.add("CLEANUP:" + kind + "_REINSPECT_FAILED");
        }
    }

    private static boolean isClosed(AutoCloseable resource) throws SQLException {
        if (resource instanceof ResultSet resultSet) {
            return resultSet.isClosed();
        }
        if (resource instanceof Statement statement) {
            return statement.isClosed();
        }
        return ((Connection) resource).isClosed();
    }

    private static String kind(AutoCloseable resource) {
        return resource instanceof ResultSet ? "RESULTSET" : resource instanceof Statement ? "STATEMENT" : "CONNECTION";
    }

    /** Flyway whose only test-double behavior is recording and refusing a forbidden migrate entry. */
    static final class MigrateForbiddenFlyway extends Flyway {

        final AtomicInteger migrateEntries = new AtomicInteger();

        MigrateForbiddenFlyway(DataSource dataSource) {
            super(Flyway.configure().dataSource(dataSource));
        }

        @Override
        public MigrateResult migrate() {
            migrateEntries.incrementAndGet();
            throw new AssertionError("MIGRATE:ENTERED");
        }
    }

    /**
     * Wraps this control's real connection, statements and result sets; records their cleanup calls in order. With
     * injection, Connection.close delegates first, confirms release, then throws one fixed synthetic failure.
     */
    static final class TrackingDataSource implements DataSource {

        private final DataSource delegate;
        private final boolean injectCloseFailure;
        final List<AutoCloseable> underlying = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final AtomicInteger connections = new AtomicInteger();
        final AtomicInteger injectedFailures = new AtomicInteger();

        TrackingDataSource(DataSource delegate, boolean injectCloseFailure) {
            this.delegate = delegate;
            this.injectCloseFailure = injectCloseFailure;
        }

        boolean allUnderlyingClosed() throws SQLException {
            if (underlying.isEmpty()) {
                return false;
            }
            for (AutoCloseable resource : underlying) {
                if (!isClosed(resource)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection real = delegate.getConnection();
            underlying.add(real);
            connections.incrementAndGet();
            return proxy(Connection.class, real, (proxy, method, args) -> {
                boolean noArgs = args == null || args.length == 0;
                if (method.getName().equals("close") && noArgs) {
                    events.add("CONNECTION_CLOSE");
                    real.close();
                    if (injectCloseFailure && real.isClosed() && injectedFailures.getAndIncrement() == 0) {
                        throw new SQLException("synthetic cleanup failure");
                    }
                    return null;
                }
                if (method.getName().equals("rollback") && noArgs) {
                    events.add("ROLLBACK");
                }
                Object result = invoke(real, method, args);
                if (method.getName().equals("createStatement") && result instanceof Statement statement) {
                    return trackStatement(statement);
                }
                return result;
            });
        }

        private Statement trackStatement(Statement real) {
            underlying.add(real);
            return proxy(Statement.class, real, (proxy, method, args) -> {
                if (method.getName().equals("close") && (args == null || args.length == 0)) {
                    events.add("STATEMENT_CLOSE");
                }
                Object result = invoke(real, method, args);
                if (method.getName().equals("executeQuery") && result instanceof ResultSet resultSet) {
                    return trackResultSet(resultSet);
                }
                return result;
            });
        }

        private ResultSet trackResultSet(ResultSet real) {
            underlying.add(real);
            return proxy(ResultSet.class, real, (proxy, method, args) -> {
                if (method.getName().equals("close") && (args == null || args.length == 0)) {
                    events.add("RESULTSET_CLOSE");
                }
                return invoke(real, method, args);
            });
        }

        private static <T> T proxy(Class<T> type, T real, InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
        }

        private static Object invoke(Object real, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(real, args);
            } catch (InvocationTargetException invocation) {
                throw invocation.getTargetException();
            }
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException("credentials are fixed by the control");
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("unwrap not supported");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
