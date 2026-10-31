package io.github.jiangzhengxun.taskmanager.support.testdb;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;

/**
 * Migration strategy that verifies the database identity through the DataSource Flyway is actually configured with,
 * and calls {@link Flyway#migrate()} only after verification and all JDBC cleanup succeeded.
 *
 * <p>It never adjusts driver settings; timeouts are supplied and validated before any bean is created.
 */
public final class GuardedFlywayMigrationStrategy implements FlywayMigrationStrategy {

    private final TestDatabaseIdentityPolicy policy;
    private final TestDatabaseIdentityVerifier verifier;
    private final Timeline timeline = new Timeline();

    GuardedFlywayMigrationStrategy(TestDatabaseIdentityPolicy policy) {
        this(policy, new TestDatabaseIdentityVerifier());
    }

    GuardedFlywayMigrationStrategy(TestDatabaseIdentityPolicy policy, TestDatabaseIdentityVerifier verifier) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    @Override
    public void migrate(Flyway flyway) {
        DataSource dataSource;
        try {
            dataSource = flyway.getConfiguration().getDataSource();
        } catch (RuntimeException unavailable) {
            throw new TestDatabaseGuardException(Stage.IDENTITY, Reason.IDENTITY_DATASOURCE_UNAVAILABLE);
        }
        verifier.verify(dataSource, policy);
        // verify() returns only after the identity matched AND every JDBC resource was released (verifier contract).
        timeline.record(Timeline.IDENTITY_VERIFIED_AND_RELEASED, -1);
        timeline.record(Timeline.MIGRATE_INVOKED, -1);
        MigrateResult result = flyway.migrate();
        timeline.record(Timeline.MIGRATE_RETURNED, result == null ? -1 : result.migrationsExecuted);
    }

    /** Context-local observation timeline of this strategy instance (one strategy bean per context). */
    Timeline timeline() {
        return timeline;
    }

    @Override
    public String toString() {
        return "GuardedFlywayMigrationStrategy[redacted]";
    }

    /**
     * Ordered, context-local observation events. Fixed event kinds, a per-timeline sequence, the recording thread name and
     * an optional count only; never credentials, policy values, SQL or object representations.
     */
    static final class Timeline {
        static final String IDENTITY_VERIFIED_AND_RELEASED = "IDENTITY_VERIFIED_AND_RELEASED";
        static final String MIGRATE_INVOKED = "MIGRATE_INVOKED";
        static final String MIGRATE_RETURNED = "MIGRATE_RETURNED";

        record Event(long sequence, String kind, int count, String thread) {
        }

        private final List<Event> events = new ArrayList<>();
        private long next;

        synchronized void record(String kind, int count) {
            events.add(new Event(++next, kind, count, Thread.currentThread().getName()));
        }

        synchronized List<Event> events() {
            return List.copyOf(events);
        }
    }
}
