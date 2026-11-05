package io.github.jiangzhengxun.taskmanager.evidence.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jiangzhengxun.taskmanager.evidence.application.port.out.TaskEvidencePage;
import io.github.jiangzhengxun.taskmanager.evidence.application.port.out.TaskEvidenceRepository;
import io.github.jiangzhengxun.taskmanager.evidence.domain.TaskEvidence;
import io.github.jiangzhengxun.taskmanager.evidence.infrastructure.persistence.TaskEvidenceRepositoryAdapter;
import io.github.jiangzhengxun.taskmanager.support.testdb.GuardedTestDatabase;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * NO outer test transaction: each request commits (or rolls back) for real. Visibility is checked through an
 * independent observer JDBC connection (autocommit) so a flush inside the request transaction cannot be mistaken
 * for a commit. Only rows created by this class are cleaned up, by id, in @AfterEach.
 */
@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@GuardedTestDatabase
class TaskEvidenceCommitIntegrationTest {

    static final AtomicBoolean FAIL_AFTER_FLUSH = new AtomicBoolean(false);
    static final AtomicBoolean OBSERVE_IN_SERVICE_TX = new AtomicBoolean(false);
    static final AtomicBoolean OBSERVE_GET_TX = new AtomicBoolean(false);
    static final AtomicBoolean CAPTURE_POST_PID = new AtomicBoolean(false);
    static final java.util.concurrent.atomic.AtomicLong POST_PID = new java.util.concurrent.atomic.AtomicLong(0);
    static final Map<String, Object> OBS = new java.util.concurrent.ConcurrentHashMap<>();
    static final List<String> LOCK_FAILURE_SQLSTATES = new java.util.concurrent.CopyOnWriteArrayList<>();
    static final java.util.concurrent.atomic.AtomicReference<Gate> POST_GATE = new java.util.concurrent.atomic.AtomicReference<>();
    static final java.util.concurrent.atomic.AtomicReference<Gate> GET_GATE = new java.util.concurrent.atomic.AtomicReference<>();
    static final long GATE_LIMIT_SECONDS = 20;
    /** Exact own task id whose DELETE request records its transaction backend pid (0 = off). */
    static final java.util.concurrent.atomic.AtomicLong DELETE_PID_TARGET = new java.util.concurrent.atomic.AtomicLong(0);
    static final java.util.concurrent.atomic.AtomicLong DELETE_PID = new java.util.concurrent.atomic.AtomicLong(0);
    /** Set when worker exit could not be confirmed; persistent; checked before every case and before cleanup. */
    static final java.util.concurrent.atomic.AtomicReference<String> CLEANUP_BLOCKED =
            new java.util.concurrent.atomic.AtomicReference<>();
    /** Test-only per-statement limit for EntityManager observation queries (JPA hint, milliseconds). */
    static final int EM_QUERY_TIMEOUT_MILLIS = 5000;

    /** Test-only pause point keyed by an exact request attribute; bounded; records the paused backend pid. */
    static final class Gate {
        final String key;
        final java.util.concurrent.CountDownLatch arrived = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        volatile long pid;

        Gate(String key) {
            this.key = key;
        }

        long awaitArrivalPid() throws InterruptedException {
            if (!arrived.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("gate " + key + " not reached within limit");
            }
            return pid;
        }
    }

    static void pauseAt(Gate gate, String key, jakarta.persistence.EntityManager em) {
        if (gate == null || !gate.key.equals(key)) {
            return;
        }
        gate.pid = ((Number) em.createNativeQuery("SELECT pg_backend_pid()")
                .setHint("jakarta.persistence.query.timeout", EM_QUERY_TIMEOUT_MILLIS).getSingleResult()).longValue();
        gate.arrived.countDown();
        try {
            if (!gate.release.await(GATE_LIMIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new IllegalStateException("test gate not released"); // ends the request transaction (rollback)
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test gate interrupted");
        }
    }

    private static Object[] row(jakarta.persistence.EntityManager em, String sql) {
        return (Object[]) em.createNativeQuery(sql)
                .setHint("jakarta.persistence.query.timeout", EM_QUERY_TIMEOUT_MILLIS).getSingleResult();
    }

    /**
     * Wraps the real adapter: when armed, throws after the real insert+flush, inside the real service
     * transaction. Timing difference vs production: production commits right after flush; here the failure is
     * injected between flush and commit on purpose.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class FailAfterFlushConfiguration {
        @Bean
        @Primary
        TaskEvidenceRepository failAfterFlushEvidenceRepository(TaskEvidenceRepositoryAdapter delegate,
                jakarta.persistence.EntityManagerFactory entityManagerFactory) {
            // Same kind of transaction-scoped shared EntityManager the adapter gets through @PersistenceContext, so
            // observation SQL runs in the adapter's persistence context / JDBC connection (static reading; not run).
            jakarta.persistence.EntityManager em =
                    org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
            return new TaskEvidenceRepository() {
                @Override public void applyLocalLockTimeout() {
                    if (CAPTURE_POST_PID.get()) {
                        // Worker POST's own backend pid, read in its service transaction before the parent lock.
                        POST_PID.set(((Number) em.createNativeQuery("SELECT pg_backend_pid()")
                                .setHint("jakarta.persistence.query.timeout", EM_QUERY_TIMEOUT_MILLIS)
                                .getSingleResult()).longValue());
                    }
                    if (OBSERVE_IN_SERVICE_TX.get()) {
                        // Baseline of THIS session, read inside the service transaction just before the real set_config.
                        Object[] before = row(em, "SELECT pg_backend_pid(), current_setting('lock_timeout')");
                        OBS.put("beforePid", ((Number) before[0]).longValue());
                        OBS.put("baseline", before[1]);
                        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                                new org.springframework.transaction.support.TransactionSynchronization() {
                                    @Override public void afterCompletion(int status) {
                                        // Runs after commit/rollback, before Spring closes the EntityManager; with Boot's
                                        // Hibernate adapter the physical connection is held until close (static reading).
                                        OBS.put("completionStatus", status);
                                        try {
                                            Object[] after = row(em, "SELECT pg_backend_pid(), current_setting('lock_timeout'),"
                                                    + " txid_current_if_assigned()::text");
                                            OBS.put("afterPid", ((Number) after[0]).longValue());
                                            OBS.put("afterLockTimeout", after[1]);
                                            OBS.put("afterTxid", after[2] == null ? "NONE" : after[2]);
                                        } catch (RuntimeException e) {
                                            // Spring swallows afterCompletion errors; record so the test fails explicitly.
                                            OBS.put("afterError", e.getClass().getName());
                                        }
                                    }
                                });
                    }
                    delegate.applyLocalLockTimeout(); // real adapter statement; never re-set by the test
                }
                @Override public boolean lockParentTaskForKeyShare(long taskId) {
                    try {
                        return delegate.lockParentTaskForKeyShare(taskId);
                    } catch (RuntimeException real) {
                        // Real failure of the real lock statement: keep its SQLSTATE chain (no SQL after failure).
                        for (Throwable t = real; t != null; t = t.getCause()) {
                            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null) {
                                LOCK_FAILURE_SQLSTATES.add(sql.getSQLState());
                            }
                        }
                        throw real;
                    }
                }
                @Override public TaskEvidencePage findPage(long taskId, long offset, int size) {
                    // Reached only after ListTaskEvidenceService's existence check, before the page query.
                    pauseAt(GET_GATE.get(), String.valueOf(taskId), em);
                    if (!OBSERVE_GET_TX.get()) {
                        return delegate.findPage(taskId, offset, size);
                    }
                    // Brackets the REAL adapter query: same persistence context; pid and now() (transaction start
                    // time) must be identical before and after, tying both reads to the GET query's transaction.
                    String sql = "SELECT pg_backend_pid(), now()::text, current_setting('transaction_isolation'),"
                            + " current_setting('transaction_read_only'), current_setting('default_transaction_isolation')";
                    Object[] before = row(em, sql);
                    OBS.put("getSpringActive", org.springframework.transaction.support.TransactionSynchronizationManager
                            .isActualTransactionActive());
                    OBS.put("getSpringReadOnly", org.springframework.transaction.support.TransactionSynchronizationManager
                            .isCurrentTransactionReadOnly());
                    TaskEvidencePage page = delegate.findPage(taskId, offset, size);
                    Object[] after = row(em, sql);
                    OBS.put("getBeforePid", ((Number) before[0]).longValue());
                    OBS.put("getAfterPid", ((Number) after[0]).longValue());
                    OBS.put("getBeforeNow", before[1]);
                    OBS.put("getAfterNow", after[1]);
                    OBS.put("getIsolation", after[2]);
                    OBS.put("getDbReadOnly", after[3]);
                    OBS.put("getDefaultIsolation", after[4]);
                    return page;
                }
                @Override public TaskEvidence insertAndFlush(TaskEvidence evidence) {
                    // Reached only after the real FOR KEY SHARE succeeded, inside the open service transaction.
                    // Only the request whose content equals the gate key is paused (second workers pass).
                    pauseAt(POST_GATE.get(), evidence.content(), em);
                    TaskEvidence saved = delegate.insertAndFlush(evidence);
                    if (OBSERVE_IN_SERVICE_TX.get()) {
                        // After the real insert+flush, same persistence context: pid, local value, and this transaction's
                        // xid reduced to 32 bits so it can be compared with the committed row's xmin.
                        Object[] in = row(em, "SELECT pg_backend_pid(), current_setting('lock_timeout'),"
                                + " (txid_current_if_assigned() % 4294967296)::text,"
                                + " (SELECT xmin::text FROM task_evidence WHERE id = " + saved.id() + ")");
                        OBS.put("inPid", ((Number) in[0]).longValue());
                        OBS.put("inLockTimeout", in[1]);
                        OBS.put("inXid32", in[2]);
                        OBS.put("inRowXmin", in[3]);
                    }
                    if (FAIL_AFTER_FLUSH.get()) {
                        throw new IllegalStateException("injected failure after flush");
                    }
                    return saved;
                }
            };
        }

        /**
         * Test-only @Primary decorator of the real TaskRepositoryAdapter; delegates all four port methods unchanged
         * (save, findById, deleteById, findPageByIdAscending). Only a deleteById for exactly DELETE_PID_TARGET first
         * records pg_backend_pid() inside DeleteTaskService's @Transactional method through a transaction-bound shared
         * EntityManager (same mechanism the Spring Data repository uses), then calls the real deleteById.
         */
        @Bean
        @Primary
        io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository observingTaskRepository(
                io.github.jiangzhengxun.taskmanager.task.infrastructure.persistence.TaskRepositoryAdapter delegate,
                jakarta.persistence.EntityManagerFactory entityManagerFactory) {
            jakarta.persistence.EntityManager em =
                    org.springframework.orm.jpa.SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
            return new io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository() {
                @Override
                public io.github.jiangzhengxun.taskmanager.task.domain.Task save(
                        io.github.jiangzhengxun.taskmanager.task.domain.Task task) {
                    return delegate.save(task);
                }

                @Override
                public java.util.Optional<io.github.jiangzhengxun.taskmanager.task.domain.Task> findById(long id) {
                    return delegate.findById(id);
                }

                @Override
                public boolean deleteById(long id) {
                    if (id != 0 && DELETE_PID_TARGET.get() == id) {
                        DELETE_PID.set(((Number) em.createNativeQuery("SELECT pg_backend_pid()")
                                .setHint("jakarta.persistence.query.timeout", EM_QUERY_TIMEOUT_MILLIS)
                                .getSingleResult()).longValue());
                    }
                    return delegate.deleteById(id); // real product delete, unchanged
                }

                @Override
                public io.github.jiangzhengxun.taskmanager.task.application.model.TaskPage findPageByIdAscending(
                        int page, int size) {
                    return delegate.findPageByIdAscending(page, size);
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper objectMapper;

    private final List<Long> createdTaskIds = new ArrayList<>();

    @BeforeEach
    void requireUtf8() throws Exception {
        // Checked FIRST: before any shared state is reset, any connection is taken, data is created or a request runs.
        requireNoUnexitedWorker();
        FAIL_AFTER_FLUSH.set(false);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            assertThat(single(s, "SHOW server_encoding")).isEqualTo("UTF8");
            assertThat(single(s, "SHOW client_encoding")).isEqualTo("UTF8");
        }
    }

    @AfterEach
    void cleanupOwnRows() throws Exception {
        String blocked = CLEANUP_BLOCKED.get(); // read, never cleared here: the block stays in force
        if (blocked != null) {
            // Only end pause conditions (helps the stuck worker leave); no state reset, no SQL, no row deletion.
            releaseAndClear(POST_GATE);
            releaseAndClear(GET_GATE);
            List<Long> uncleaned = List.copyOf(createdTaskIds);
            createdTaskIds.clear();
            throw new AssertionError("own-id cleanup NOT performed: " + blocked + "; uncleaned own task ids: " + uncleaned
                    + ". Stop the test run; do not re-run in this JVM.");
        }
        FAIL_AFTER_FLUSH.set(false);
        OBSERVE_IN_SERVICE_TX.set(false);
        OBSERVE_GET_TX.set(false);
        CAPTURE_POST_PID.set(false);
        POST_PID.set(0);
        OBS.clear();
        LOCK_FAILURE_SQLSTATES.clear();
        releaseAndClear(POST_GATE);
        releaseAndClear(GET_GATE);
        DELETE_PID_TARGET.set(0);
        DELETE_PID.set(0);
        // Only own ids. Every id is attempted on its own connection even if an earlier one fails; failures are
        // aggregated and rethrown (JUnit keeps a primary test failure and adds this one as suppressed).
        List<String> failures = new ArrayList<>();
        AssertionError aggregate = new AssertionError("cleanup failed for own task ids");
        for (long id : createdTaskIds) {
            try (Connection c = dataSource.getConnection();
                    PreparedStatement del = c.prepareStatement("DELETE FROM tasks WHERE id = ?");
                    PreparedStatement chk = c.prepareStatement(
                            "SELECT (SELECT count(*) FROM tasks WHERE id = ?) + (SELECT count(*) FROM task_evidence WHERE task_id = ?)")) {
                c.setAutoCommit(true);
                del.setQueryTimeout(SQL_LIMIT_SECONDS);
                chk.setQueryTimeout(SQL_LIMIT_SECONDS);
                del.setLong(1, id);
                del.executeUpdate();
                chk.setLong(1, id);
                chk.setLong(2, id);
                try (ResultSet rs = chk.executeQuery()) {
                    if (!rs.next() || rs.getLong(1) != 0) {
                        failures.add("task " + id + ": rows remain");
                    }
                }
            } catch (Exception | AssertionError e) {
                failures.add("task " + id + ": " + e.getClass().getSimpleName());
                aggregate.addSuppressed(e);
            }
        }
        createdTaskIds.clear();
        if (!failures.isEmpty()) {
            throw new AssertionError(aggregate.getMessage() + " " + failures, aggregate);
        }
    }

    private static String single(Statement s, String sql) throws Exception {
        s.setQueryTimeout(SQL_LIMIT_SECONDS);
        try (ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private long committedTask(String title) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("INSERT INTO tasks (title) VALUES (?) RETURNING id")) {
            c.setAutoCommit(true);
            ps.setQueryTimeout(SQL_LIMIT_SECONDS);
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                long id = rs.getLong(1);
                createdTaskIds.add(id);
                return id;
            }
        }
    }

    /** All task columns plus xmin (additional observation only; xmax is not compared because row locks set it). */
    private Map<String, Object> observeTask(long id) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT t.*, t.xmin::text AS xmin_text FROM tasks t WHERE id = ?")) {
            c.setAutoCommit(true);
            ps.setQueryTimeout(SQL_LIMIT_SECONDS);
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                return row;
            }
        }
    }

    private long observeEvidenceCount(long taskId) throws Exception {
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM task_evidence WHERE task_id = ?")) {
            c.setAutoCommit(true);
            ps.setQueryTimeout(SQL_LIMIT_SECONDS);
            ps.setLong(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private MvcResult postEvidence(long taskId, String json) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + taskId + "/evidence")
                .contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    @Test
    void committedPostIsVisibleToIndependentObserverAndMatchesResponse() throws Exception {
        long taskId = committedTask("commit");
        Map<String, Object> taskBefore = observeTask(taskId);

        // Observer connection obtained and held BEFORE the POST: autocommit READ COMMITTED, so every statement takes
        // a fresh snapshot after the request transaction ended; no snapshot from before the POST can be reused.
        // No maximum-pool-size is configured in application.yml or the guarded initializer, so Hikari's default
        // applies; asserted here at runtime instead of assumed: the request still needs one more connection.
        assertThat(dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getMaximumPoolSize())
                .as("pool can serve the request while the observer is held").isGreaterThanOrEqualTo(2);
        try (Connection observer = dataSource.getConnection()) {
            observer.setAutoCommit(true);
            observer.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            long observerPid;
            try (Statement s = observer.createStatement()) {
                s.setQueryTimeout(SQL_LIMIT_SECONDS);
                try (ResultSet rs = s.executeQuery("SELECT pg_backend_pid()")) {
                    rs.next();
                    observerPid = rs.getLong(1);
                }
            }

            MvcResult result = postEvidence(taskId, "{\"content\":\"\\u3000\\uD83D\\uDE00 proof\\n line \"}");
            assertThat(result.getResponse().getStatus()).isEqualTo(201); // status before parsing
            assertThat(result.getResponse().getHeader("Location")).isNull();
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
            assertThat(body.size()).isEqualTo(4);
            long evidenceId = body.get("id").asLong();

            try (PreparedStatement ps = observer.prepareStatement(
                    "SELECT id, task_id, content, created_at, pg_backend_pid() AS pid FROM task_evidence WHERE id = ?")) {
                ps.setLong(1, evidenceId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).as("committed row visible to the pre-held observer").isTrue();
                    assertThat(rs.getLong("pid")).as("same observer session").isEqualTo(observerPid);
                    assertThat(rs.getLong("id")).isEqualTo(evidenceId);
                    assertThat(rs.getLong("task_id")).isEqualTo(taskId).isEqualTo(body.get("taskId").asLong());
                    assertThat(rs.getString("content")).isEqualTo("😀 proof\n line").isEqualTo(body.get("content").asText());
                    java.time.Instant stored = rs.getObject("created_at", java.time.OffsetDateTime.class).toInstant();
                    java.time.Instant responded = java.time.Instant.parse(body.get("createdAt").asText());
                    // No truncation/rounding of the response: extra nanoseconds in the response must fail.
                    assertThat(responded.getNano() % 1_000).as("response createdAt has microsecond precision").isZero();
                    assertThat(responded).as("response createdAt equals stored createdAt exactly").isEqualTo(stored);
                    assertThat(rs.next()).isFalse();
                }
            }
        }
        Map<String, Object> taskAfter = observeTask(taskId);
        assertThat(taskAfter).isEqualTo(taskBefore); // all columns incl. xmin; xmin limits documented in TEST_PLAN
    }

    /**
     * lock_timeout observed INSIDE the real create-service transaction (test-only decorator, after insert+flush).
     * Restoration after the transaction is NOT asserted here: the pooled physical session cannot be reliably
     * re-acquired, so a value read on another connection would not be evidence. See TEST_PLAN limitations.
     */
    @Test
    void createServiceTransactionAppliesLocalLockTimeout() throws Exception {
        long taskId = committedTask("lock-timeout");
        OBSERVE_IN_SERVICE_TX.set(true);
        MvcResult result = postEvidence(taskId, "{\"content\":\"lock\"}");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        long evidenceId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asLong();

        // Session identity: baseline, in-transaction and after-completion reads come from ONE backend pid.
        assertThat(OBS).doesNotContainKey("afterError");
        long pid = (Long) OBS.get("beforePid");
        assertThat(OBS.get("inPid")).isEqualTo(pid);
        assertThat(OBS.get("afterPid")).isEqualTo(pid);
        // Transaction identity: the committed row's xmin (read by an independent connection) equals the xid observed
        // inside the transaction, and the row seen in-transaction carries the same xmin.
        String committedXmin;
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement("SELECT xmin::text FROM task_evidence WHERE id = ?")) {
            c.setAutoCommit(true);
            ps.setQueryTimeout(SQL_LIMIT_SECONDS);
            ps.setLong(1, evidenceId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                committedXmin = rs.getString(1);
            }
        }
        assertThat(OBS.get("inXid32")).as("xid assigned in the observed transaction").isNotNull().isEqualTo(committedXmin);
        assertThat(OBS.get("inRowXmin")).isEqualTo(committedXmin);
        // Local setting inside, restored afterwards on the same session, compared with that session's own baseline.
        assertThat(OBS.get("inLockTimeout")).isEqualTo("5s");
        assertThat(OBS.get("baseline")).as("baseline differs from the local value").isNotEqualTo("5s");
        assertThat(OBS.get("completionStatus"))
                .isEqualTo(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED);
        // NONE only means no transaction ID is currently assigned; on its own it does not prove that no transaction
        // exists. It is used together with completion status, pid identity and the restored value.
        assertThat(OBS.get("afterTxid")).as("no transaction ID currently assigned").isEqualTo("NONE");
        assertThat(OBS.get("afterLockTimeout")).as("restored to this session's baseline").isEqualTo(OBS.get("baseline"));
        assertThat(observeEvidenceCount(taskId)).isEqualTo(1);
    }

    @Test
    void identicalPostsCreateDistinctRows() throws Exception {
        long taskId = committedTask("dup");
        MvcResult ra = postEvidence(taskId, "{\"content\":\"same\"}");
        assertThat(ra.getResponse().getStatus()).isEqualTo(201); // status before parsing
        MvcResult rb = postEvidence(taskId, "{\"content\":\"same\"}");
        assertThat(rb.getResponse().getStatus()).isEqualTo(201);
        JsonNode a = objectMapper.readTree(ra.getResponse().getContentAsString());
        JsonNode b = objectMapper.readTree(rb.getResponse().getContentAsString());
        assertThat(a.get("id").asLong()).isNotEqualTo(b.get("id").asLong());
        assertThat(observeEvidenceCount(taskId)).isEqualTo(2);
    }

    @Test
    void invalidInputIs400BeforeMissingTask404() throws Exception {
        long missing = committedTask("missing-probe");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("DELETE FROM tasks WHERE id = ?")) {
            c.setAutoCommit(true);
            ps.setLong(1, missing);
            ps.executeUpdate();
        }
        mockMvc.perform(post("/api/tasks/" + missing + "/evidence").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("content must not be blank"));
        mockMvc.perform(post("/api/tasks/" + missing + "/evidence").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"a\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tasks/" + missing + "/evidence").param("page", String.valueOf(Integer.MAX_VALUE)))
                .andExpect(status().isNotFound());
    }

    @Test
    void failureAfterFlushRollsBackRealTransaction() throws Exception {
        long taskId = committedTask("rollback");
        Map<String, Object> taskBefore = observeTask(taskId);
        FAIL_AFTER_FLUSH.set(true);
        MvcResult result = postEvidence(taskId, "{\"content\":\"will roll back\"}");
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("injected").doesNotContain("will roll back");
        assertThat(observeEvidenceCount(taskId)).as("rolled back row invisible").isZero();
        assertThat(observeTask(taskId)).isEqualTo(taskBefore);
    }

    @Test
    void hugeOffsetOnExistingTaskIsEmptyPage() throws Exception {
        long taskId = committedTask("offset");
        mockMvc.perform(get("/api/tasks/" + taskId + "/evidence").param("page", String.valueOf(Integer.MAX_VALUE)).param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    /**
     * GET through the real controller, ListTaskEvidenceService (@Transactional(readOnly = true)) and adapter.
     * Spring's read-only flag and PostgreSQL's transaction_read_only are recorded and judged separately.
     * Expected isolation is the session's own default_transaction_isolation (no isolation is declared by the
     * service; no REPEATABLE READ or cross-page snapshot contract is added).
     */
    @Test
    void getRunsInRealReadOnlyTransactionWithDefaultIsolation() throws Exception {
        long taskId = committedTask("get-tx");
        OBSERVE_GET_TX.set(true);
        mockMvc.perform(get("/api/tasks/" + taskId + "/evidence"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        assertThat(OBS.get("getBeforePid")).as("observations bracket the GET query on one session")
                .isNotNull().isEqualTo(OBS.get("getAfterPid"));
        assertThat(OBS.get("getBeforeNow")).as("same transaction (transaction start time)").isEqualTo(OBS.get("getAfterNow"));
        // Spring layer
        assertThat(OBS.get("getSpringActive")).isEqualTo(Boolean.TRUE);
        assertThat(OBS.get("getSpringReadOnly")).as("Spring transaction readOnly flag").isEqualTo(Boolean.TRUE);
        // Database layer (expectation: JPA dialect sets Connection.setReadOnly(true) for readOnly transactions and
        // pgJDBC's default readOnlyMode starts the transaction READ ONLY; static reading, not verified here)
        assertThat(OBS.get("getDbReadOnly")).as("PostgreSQL transaction_read_only").isEqualTo("on");
        assertThat(OBS.get("getIsolation")).as("isolation equals session default").isEqualTo(OBS.get("getDefaultIsolation"));
    }

    /**
     * Regression with the Evidence converter actually loaded in this full context: exactly one instance (its list
     * position is not asserted), reads only CreateTaskEvidenceRequest, never writes; the HTTP contract (201 / fixed
     * 400s, trailing-token cases discriminating) holds; existing Task endpoints keep their status codes, error
     * contract and JSON timestamps. A replay of the framework's read selection is printed as a diagnostic only.
     */
    @Test
    void evidenceConverterIsLoadedAndTaskEndpointsKeepTheirContract() throws Exception {
        List<org.springframework.http.converter.HttpMessageConverter<?>> converters = handlerAdapter.getMessageConverters();
        // 1. Exactly one Evidence converter in the full context (its list position is not part of the contract).
        assertThat(converters).filteredOn(c -> c instanceof EvidenceRequestBodyConverter).hasSize(1);
        int evidenceIndex = -1;
        for (int i = 0; i < converters.size(); i++) {
            if (converters.get(i) instanceof EvidenceRequestBodyConverter) {
                evidenceIndex = i;
            }
        }
        // 2. DIAGNOSTIC ONLY (no assertion): a replay, from static knowledge of Spring 6.2's
        //    AbstractMessageConverterMethodArgumentResolver (not verified against its source), of the read selection
        //    for the controller's @RequestBody parameter: Generic -> canRead(Type, contextClass, mediaType), else
        //    Smart -> canRead(ResolvableType, mediaType), else canRead(Class, mediaType). It is not the framework's
        //    real dispatch; the HTTP cases below are the acceptance evidence.
        java.lang.reflect.Method create = TaskEvidenceController.class.getMethod("create", long.class,
                CreateTaskEvidenceRequest.class);
        org.springframework.core.MethodParameter bodyParameter = new org.springframework.core.MethodParameter(create, 1);
        String firstReader = null;
        for (int i = 0; i < converters.size() && firstReader == null; i++) {
            org.springframework.http.converter.HttpMessageConverter<?> c = converters.get(i);
            boolean reads;
            if (c instanceof org.springframework.http.converter.GenericHttpMessageConverter<?> g) {
                reads = g.canRead(CreateTaskEvidenceRequest.class, TaskEvidenceController.class, MediaType.APPLICATION_JSON);
            } else if (c instanceof org.springframework.http.converter.SmartHttpMessageConverter<?> s) {
                reads = s.canRead(org.springframework.core.ResolvableType.forMethodParameter(bodyParameter),
                        MediaType.APPLICATION_JSON);
            } else {
                reads = c.canRead(CreateTaskEvidenceRequest.class, MediaType.APPLICATION_JSON);
            }
            System.out.println("CONVERTER-DIAG [" + i + "] " + c.getClass().getName() + " readsEvidenceDto=" + reads);
            if (reads) {
                firstReader = c.getClass().getName();
            }
        }
        System.out.println("CONVERTER-DIAG evidenceIndex=" + evidenceIndex + " firstReader=" + firstReader
                + " (diagnostic replay, not asserted)");
        EvidenceRequestBodyConverter evidence = (EvidenceRequestBodyConverter) converters.get(evidenceIndex);
        assertThat(evidence.canRead(CreateTaskEvidenceRequest.class, MediaType.APPLICATION_JSON)).isTrue();

        // 3./4. Observable contract over HTTP on an own committed task. Duplicate and decoded-duplicate keys are also
        //    rejected by the DTO's own deserializer under any Jackson converter; the trailing token after a valid
        //    object is the case only the dedicated reader (FAIL_ON_TRAILING_TOKENS) rejects.
        long evidenceTask = committedTask("converter-contract");
        assertThat(postEvidence(evidenceTask, "{\"content\":\"converter ok\"}").getResponse().getStatus()).isEqualTo(201);
        for (String bad : new String[] {"{\"content\":\"a\",\"content\":\"b\"}",
                "{\"content\":\"a\",\"\\u0063ontent\":\"b\"}", "{\"content\":\"a\"} {\"content\":\"b\"}",
                "{\"content\":\"a\"}]"}) {
            MvcResult rejected = postEvidence(evidenceTask, bad);
            assertThat(rejected.getResponse().getStatus()).as("rejected: %s", bad).isEqualTo(400);
            assertThat(objectMapper.readTree(rejected.getResponse().getContentAsString()).get("message").asText())
                    .as("fixed message: %s", bad).isEqualTo("Malformed JSON request");
        }
        assertThat(observeEvidenceCount(evidenceTask)).as("only the valid POST stored a row").isEqualTo(1);
        for (Class<?> other : new Class<?>[] {Object.class, Map.class, TaskEvidenceResponse.class, String.class}) {
            assertThat(evidence.canRead(other, MediaType.APPLICATION_JSON)).as("reads only the Evidence DTO").isFalse();
            assertThat(evidence.canWrite(other, MediaType.APPLICATION_JSON)).isFalse();
        }

        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"  converter regression  \",\"description\":\"d\"}"))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsString());
        long id = body.get("id").asLong();
        createdTaskIds.add(id);
        assertThat(body.get("title").asText()).isEqualTo("converter regression");
        assertThat(body.get("status").asText()).isEqualTo("TODO");
        java.time.Instant.parse(body.get("createdAt").asText()); // ISO-8601 string, not numeric
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/tasks/" + id);

        mockMvc.perform(get("/api/tasks/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"status\":\"COMPLETED\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/tasks"));
        mockMvc.perform(post("/api/tasks").contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Autowired
    private org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter handlerAdapter;

    // ---- Controlled concurrency: matrix F (7 rows, evidence-r3/TEST_PLAN.md) ----

    private static final long WAIT_LIMIT_NANOS = java.time.Duration.ofSeconds(10).toNanos();
    private static final long RESULT_LIMIT_SECONDS = 15;
    /** JDBC-level limit for every test-owned SQL (probe / blocker); the driver cancels the statement on expiry. */
    private static final int SQL_LIMIT_SECONDS = 5;

    private static void releaseAndClear(java.util.concurrent.atomic.AtomicReference<Gate> ref) {
        Gate gate = ref.getAndSet(null);
        if (gate != null) {
            gate.release.countDown();
        }
    }

    /**
     * Fails fast when an earlier case could not confirm its workers exited. Persistent for this class's JVM; there is
     * no automatic recovery (a later run must be stopped, not re-run in the same JVM).
     */
    private static void requireNoUnexitedWorker() {
        String blocked = CLEANUP_BLOCKED.get();
        if (blocked != null) {
            throw new AssertionError("test run blocked by an earlier case: " + blocked);
        }
    }

    /**
     * Owns the worker executor and the gates. close(): release gates and pid targets (ends every pause condition),
     * orderly shutdown with bounded await, then shutdownNow (interrupts only this executor's threads; a request, not
     * proof of exit) with a second bounded await. No pg_cancel_backend: once a request ends its connection returns to
     * the pool and may be reused, so a recorded pid cannot be proven to still belong to the worker. Exit is accepted
     * only when isTerminated() is true; otherwise CLEANUP_BLOCKED is set (never cleared in this class) and the test
     * fails. An interrupt during an await does not skip later steps; the interrupt status is restored at the end.
     * Executor threads are not daemon threads.
     */
    private final class Workers implements AutoCloseable {
        final java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);

        <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> work) {
            return executor.submit(work);
        }

        Gate gate(java.util.concurrent.atomic.AtomicReference<Gate> ref, String key) {
            Gate gate = new Gate(key);
            assertThat(ref.compareAndSet(null, gate)).as("no stale gate").isTrue();
            return gate;
        }

        @Override
        public void close() throws Exception {
            List<Throwable> problems = new ArrayList<>();
            boolean interrupted = false;
            releaseAndClear(POST_GATE);
            releaseAndClear(GET_GATE);
            CAPTURE_POST_PID.set(false);
            DELETE_PID_TARGET.set(0);
            executor.shutdown();
            try {
                executor.awaitTermination(RESULT_LIMIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true; // flag cleared by the throw, so the next await still waits
                problems.add(e);
            }
            if (!executor.isTerminated()) {
                executor.shutdownNow();
                try {
                    executor.awaitTermination(RESULT_LIMIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                    problems.add(e);
                }
            }
            if (!executor.isTerminated()) {
                CLEANUP_BLOCKED.compareAndSet(null, "worker exit not confirmed after bounded shutdown");
                problems.add(new AssertionError("worker exit not confirmed; own-id cleanup and later cases are blocked"));
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            if (!problems.isEmpty()) {
                AssertionError failure = new AssertionError("concurrency resource cleanup failed");
                problems.forEach(failure::addSuppressed);
                throw failure; // try-with-resources attaches this to a primary failure as suppressed
            }
        }
    }

    /** Test-owned JDBC transaction; close() always rolls back if still open, then closes. */
    private final class Blocker implements AutoCloseable {
        final Connection connection;
        final long pid;

        Blocker() throws Exception {
            Connection c = dataSource.getConnection();
            try {
                c.setAutoCommit(false);
                try (Statement s = c.createStatement()) {
                    s.setQueryTimeout(SQL_LIMIT_SECONDS);
                    try (ResultSet rs = s.executeQuery("SELECT pg_backend_pid()")) {
                        rs.next();
                        this.pid = rs.getLong(1);
                    }
                }
            } catch (Exception e) {
                try {
                    c.close();
                } catch (Exception closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
            this.connection = c;
        }

        void deleteTask(long taskId) throws Exception {
            try (PreparedStatement d = connection.prepareStatement("DELETE FROM tasks WHERE id = ?")) {
                d.setQueryTimeout(SQL_LIMIT_SECONDS);
                d.setLong(1, taskId);
                assertThat(d.executeUpdate()).isOne();
            }
        }

        @Override
        public void close() throws Exception {
            // rollback/close are NOT bounded by setQueryTimeout. The guarded driver socketTimeout (15 s) only limits a
            // single blocking socket read; it is NOT a guarantee for the total duration of commit/rollback/close
            // (static reading; not verified at runtime).
            Exception failure = null;
            try {
                if (!connection.isClosed() && !connection.getAutoCommit()) {
                    connection.rollback(); // releases the lock when the test did not end the transaction
                }
            } catch (Exception rollbackFailure) {
                failure = rollbackFailure;
            }
            try {
                connection.close();
            } catch (Exception closeFailure) {
                if (failure == null) {
                    failure = closeFailure;
                } else {
                    failure.addSuppressed(closeFailure); // close never hides the rollback failure
                }
            }
            if (failure != null) {
                throw failure; // try-with-resources suppresses it onto a primary test failure
            }
        }
    }

    private void assertPoolAtLeast(int connections) throws Exception {
        assertThat(dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getMaximumPoolSize())
                .as("distinct connections needed by this case").isGreaterThanOrEqualTo(connections);
    }

    /** DB oracle of a real wait: blockerPid appears in pg_blocking_pids(waiterPid). Sleep is only the poll interval. */
    private void awaitBlocked(long waiterPid, long blockerPid, java.util.concurrent.Future<?> waiter) throws Exception {
        long deadline = System.nanoTime() + WAIT_LIMIT_NANOS;
        try (Connection probe = dataSource.getConnection();
                PreparedStatement ps = probe.prepareStatement("SELECT ?::int = ANY(pg_blocking_pids(?::int))")) {
            probe.setAutoCommit(true);
            ps.setQueryTimeout(SQL_LIMIT_SECONDS);
            while (true) {
                if (waiter.isDone()) {
                    waiter.get(0, java.util.concurrent.TimeUnit.SECONDS); // propagates a worker failure
                    throw new AssertionError("waiter finished before it was observed blocked");
                }
                ps.setLong(1, blockerPid);
                ps.setLong(2, waiterPid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean(1)) {
                        return;
                    }
                }
                if (System.nanoTime() > deadline) {
                    throw new AssertionError("pid " + waiterPid + " not blocked by " + blockerPid + " within limit");
                }
                Thread.sleep(20);
            }
        }
    }

    /** Waits for the DELETE request's own backend pid, recorded inside its service transaction (direct identity). */
    private long awaitDeletePid(java.util.concurrent.Future<?> delete) throws Exception {
        long deadline = System.nanoTime() + WAIT_LIMIT_NANOS;
        while (DELETE_PID.get() == 0) {
            if (delete.isDone()) {
                delete.get(0, java.util.concurrent.TimeUnit.SECONDS);
                throw new AssertionError("DELETE finished before its pid was recorded");
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("DELETE pid not recorded within limit");
            }
            Thread.sleep(20);
        }
        return DELETE_PID.get();
    }

    private static <T> T result(java.util.concurrent.Future<T> f) throws Exception {
        return f.get(RESULT_LIMIT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
    }

    enum BlockerEnd { COMMIT, ROLLBACK, HOLD_UNTIL_POST_RESULT }

    /** Rows 1–3: an uncommitted JDBC DELETE holds the row lock; a worker POST waits on it (pid-correlated). */
    private MvcResult postAgainstUncommittedDelete(long taskId, BlockerEnd end) throws Exception {
        assertPoolAtLeast(3);
        try (Workers workers = new Workers(); Blocker blocker = new Blocker()) {
            blocker.deleteTask(taskId);
            POST_PID.set(0);
            CAPTURE_POST_PID.set(true);
            java.util.concurrent.Future<MvcResult> post =
                    workers.submit(() -> postEvidence(taskId, "{\"content\":\"waiting\"}"));
            long postPid = awaitPostPid(post);
            awaitBlocked(postPid, blocker.pid, post);
            if (end == BlockerEnd.COMMIT) {
                blocker.connection.commit();
            } else if (end == BlockerEnd.ROLLBACK) {
                blocker.connection.rollback();
            }
            return result(post); // worker failures surface as ExecutionException
        }
    }

    private long awaitPostPid(java.util.concurrent.Future<?> post) throws Exception {
        long deadline = System.nanoTime() + WAIT_LIMIT_NANOS;
        while (POST_PID.get() == 0) {
            if (post.isDone()) {
                post.get(0, java.util.concurrent.TimeUnit.SECONDS);
                throw new AssertionError("POST finished before its pid was recorded");
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("POST pid not recorded within limit");
            }
            Thread.sleep(20);
        }
        return POST_PID.get();
    }

    /** Row 1. */
    @Test
    void waitingPostReturns404AfterBlockingDeleteCommits() throws Exception {
        long taskId = committedTask("conc-delete-commit");
        MvcResult result = postAgainstUncommittedDelete(taskId, BlockerEnd.COMMIT);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(observeEvidenceCount(taskId)).isZero();
    }

    /** Row 2. */
    @Test
    void waitingPostReturns201AfterBlockingDeleteRollsBack() throws Exception {
        long taskId = committedTask("conc-delete-rollback");
        MvcResult result = postAgainstUncommittedDelete(taskId, BlockerEnd.ROLLBACK);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(observeEvidenceCount(taskId)).isEqualTo(1);
    }

    /** Row 3: the real lock statement fails with SQLSTATE 55P03 (captured from its cause chain) -> safe 500. */
    @Test
    void waitingPostTimesOutSafelyWhileDeleteHoldsLock() throws Exception {
        long taskId = committedTask("conc-timeout");
        MvcResult result = postAgainstUncommittedDelete(taskId, BlockerEnd.HOLD_UNTIL_POST_RESULT);
        assertThat(LOCK_FAILURE_SQLSTATES).as("real lock failure cause").contains("55P03");
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("message").asText()).isEqualTo("An unexpected error occurred");
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("55P03").doesNotContain("lock timeout").doesNotContain("org.postgresql")
                .doesNotContain("waiting");
        assertThat(observeEvidenceCount(taskId)).isZero();
    }

    /** Row 4: POST paused after FOR KEY SHARE (transaction open); the real DELETE endpoint is blocked by that pid. */
    @Test
    void postHoldingKeyShareBlocksDeleteUntilItCommits() throws Exception {
        long taskId = committedTask("conc-post-first");
        assertPoolAtLeast(3);
        try (Workers workers = new Workers()) {
            Gate gate = workers.gate(POST_GATE, "gated-first");
            java.util.concurrent.Future<MvcResult> post =
                    workers.submit(() -> postEvidence(taskId, "{\"content\":\"gated-first\"}"));
            long postPid = gate.awaitArrivalPid();
            DELETE_PID.set(0);
            DELETE_PID_TARGET.set(taskId); // exact own task id: only this DELETE request records its pid
            java.util.concurrent.Future<MvcResult> delete =
                    workers.submit(() -> mockMvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/tasks/" + taskId))
                            .andReturn());
            // Direct identity: pid recorded inside the real DeleteTaskService transaction for this task id.
            long deletePid = awaitDeletePid(delete);
            assertThat(deletePid).as("DELETE runs on its own backend").isNotEqualTo(postPid);
            awaitBlocked(deletePid, postPid, delete); // DB oracle: blocked by exactly the paused POST backend
            assertThat(post.isDone()).as("POST still holds its transaction").isFalse();
            gate.release.countDown();
            assertThat(result(post).getResponse().getStatus()).isEqualTo(201);
            assertThat(result(delete).getResponse().getStatus()).isEqualTo(204);
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement ps =
                c.prepareStatement("SELECT count(*) FROM tasks WHERE id = ?")) {
            ps.setQueryTimeout(SQL_LIMIT_SECONDS);
            ps.setLong(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
        }
        assertThat(observeEvidenceCount(taskId)).as("evidence removed by cascade after DELETE").isZero();
    }

    /** Row 5: a second POST completes while the first POST still holds FOR KEY SHARE in an open transaction. */
    @Test
    void secondPostCompletesWhileFirstPostHoldsKeyShare() throws Exception {
        long taskId = committedTask("conc-two-posts");
        assertPoolAtLeast(3);
        try (Workers workers = new Workers()) {
            Gate gate = workers.gate(POST_GATE, "gated-first");
            java.util.concurrent.Future<MvcResult> first =
                    workers.submit(() -> postEvidence(taskId, "{\"content\":\"gated-first\"}"));
            gate.awaitArrivalPid();
            // Content differs from the gate key, so the second request is not paused.
            MvcResult second = result(workers.submit(() -> postEvidence(taskId, "{\"content\":\"second\"}")));
            assertThat(second.getResponse().getStatus()).isEqualTo(201);
            assertThat(first.isDone()).as("first POST still paused with its lock held").isFalse();
            assertThat(gate.release.getCount()).isOne();
            gate.release.countDown();
            MvcResult firstResult = result(first);
            assertThat(firstResult.getResponse().getStatus()).isEqualTo(201);
            JsonNode a = objectMapper.readTree(firstResult.getResponse().getContentAsString());
            JsonNode b = objectMapper.readTree(second.getResponse().getContentAsString());
            assertThat(a.get("id").asLong()).isNotEqualTo(b.get("id").asLong());
            assertThat(a.get("content").asText()).isEqualTo("gated-first");
            assertThat(b.get("content").asText()).isEqualTo("second");
        }
        assertThat(observeEvidenceCount(taskId)).isEqualTo(2);
    }

    /** Row 6: the real PATCH endpoint completes while a POST holds FOR KEY SHARE (FOR NO KEY UPDATE is compatible). */
    @Test
    void patchStatusCompletesWhilePostHoldsKeyShare() throws Exception {
        long taskId = committedTask("conc-patch");
        assertPoolAtLeast(3);
        try (Workers workers = new Workers()) {
            Gate gate = workers.gate(POST_GATE, "gated-first");
            java.util.concurrent.Future<MvcResult> post =
                    workers.submit(() -> postEvidence(taskId, "{\"content\":\"gated-first\"}"));
            gate.awaitArrivalPid();
            MvcResult patch = result(workers.submit(() -> mockMvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/tasks/" + taskId + "/status")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"IN_PROGRESS\"}")).andReturn()));
            assertThat(patch.getResponse().getStatus()).isEqualTo(200);
            assertThat(objectMapper.readTree(patch.getResponse().getContentAsString()).get("status").asText())
                    .isEqualTo("IN_PROGRESS");
            assertThat(post.isDone()).as("POST still paused with its lock held").isFalse();
            gate.release.countDown();
            assertThat(result(post).getResponse().getStatus()).isEqualTo(201);
        }
        // The status change is PATCH's own contract; Evidence POST itself does not modify the task row.
        assertThat(observeTask(taskId).get("status")).isEqualTo("IN_PROGRESS");
        assertThat(observeEvidenceCount(taskId)).isEqualTo(1);
    }

    /** Row 7: GET paused after its existence check; Task deleted and committed; GET then returns 200 [] / false. */
    @Test
    void getAfterExistenceCheckSeesCommittedDeleteAsEmptyPageThen404() throws Exception {
        long taskId = committedTask("conc-get");
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO task_evidence (task_id, content, created_at) VALUES (?, 'pre', now())")) {
            c.setAutoCommit(true);
            ps.setQueryTimeout(SQL_LIMIT_SECONDS);
            ps.setLong(1, taskId);
            ps.executeUpdate();
        }
        assertPoolAtLeast(3);
        try (Workers workers = new Workers()) {
            Gate gate = workers.gate(GET_GATE, String.valueOf(taskId));
            java.util.concurrent.Future<MvcResult> get =
                    workers.submit(() -> mockMvc.perform(get("/api/tasks/" + taskId + "/evidence")).andReturn());
            gate.awaitArrivalPid();
            try (Connection c = dataSource.getConnection(); PreparedStatement d =
                    c.prepareStatement("DELETE FROM tasks WHERE id = ?")) {
                c.setAutoCommit(true); // committed before the GET continues
                d.setQueryTimeout(SQL_LIMIT_SECONDS);
                d.setLong(1, taskId);
                assertThat(d.executeUpdate()).isOne();
            }
            assertThat(get.isDone()).isFalse();
            gate.release.countDown();
            MvcResult paused = result(get);
            assertThat(paused.getResponse().getStatus()).isEqualTo(200);
            assertThat(paused.getResponse().getHeader("X-Has-Next-Page")).isEqualTo("false");
            assertThat(objectMapper.readTree(paused.getResponse().getContentAsString()).size()).isZero();
        }
        mockMvc.perform(get("/api/tasks/" + taskId + "/evidence")).andExpect(status().isNotFound());
    }

    /** PostgreSQL semantics of the exact statement used by the adapter: local to the transaction, restored after. */
    @Test
    void transactionLocalLockTimeoutIsRestoredAfterTransaction() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            String original = single(s, "SHOW lock_timeout");
            c.setAutoCommit(false);
            try {
                single(s, "SELECT set_config('lock_timeout', '5000ms', true)");
                assertThat(single(s, "SHOW lock_timeout")).isEqualTo("5s");
            } finally {
                c.rollback();
                c.setAutoCommit(true);
            }
            assertThat(single(s, "SHOW lock_timeout")).isEqualTo(original);
        }
    }
}
