package io.github.jiangzhengxun.taskmanager.evidence.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jiangzhengxun.taskmanager.evidence.application.port.out.TaskEvidencePage;
import io.github.jiangzhengxun.taskmanager.evidence.domain.TaskEvidence;
import io.github.jiangzhengxun.taskmanager.support.testdb.GuardedTestDatabase;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs inside @DataJpaTest's outer test transaction (rolled back): proves schema, mapping and query semantics
 * only. It makes NO commit claim. Each test creates its own task rows; no table-wide deletes, no fixed ids.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TaskEvidenceRepositoryAdapter.class) // JdbcTemplate comes from @DataJpaTest auto-configuration
@GuardedTestDatabase
class TaskEvidenceRepositoryAdapterIntegrationTest {

    @Autowired
    private TaskEvidenceRepositoryAdapter adapter;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    /** UTF8 is a precondition of Evidence verification; checked before any Evidence DML in this class. */
    @BeforeEach
    void requireUtf8() {
        assertThat(jdbc.queryForObject("SHOW server_encoding", String.class)).isEqualTo("UTF8");
        assertThat(jdbc.queryForObject("SHOW client_encoding", String.class)).isEqualTo("UTF8");
    }

    private long newTask(String title) {
        return jdbc.queryForObject("INSERT INTO tasks (title) VALUES (?) RETURNING id", Long.class, title);
    }

    private TaskEvidence insert(long taskId, String content, String createdAt) {
        return adapter.insertAndFlush(TaskEvidence.newEvidence(taskId, content, Instant.parse(createdAt)));
    }

    @Test
    void schemaColumnsMatchV2() {
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT column_name, data_type, is_nullable, column_default, character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'task_evidence'
                ORDER BY ordinal_position""");
        assertThat(columns).extracting(c -> c.get("column_name")).containsExactly("id", "task_id", "content", "created_at");
        assertThat(columns).extracting(c -> c.get("is_nullable")).containsOnly("NO");
        assertThat(columns.get(0).get("data_type")).isEqualTo("bigint");
        assertThat(columns.get(1).get("data_type")).isEqualTo("bigint");
        assertThat(columns.get(2).get("data_type")).isEqualTo("character varying");
        assertThat(((Number) columns.get(2).get("character_maximum_length")).intValue()).isEqualTo(2000);
        assertThat(columns.get(3).get("data_type")).isEqualTo("timestamp with time zone");
        assertThat(columns.get(3).get("column_default")).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT is_identity FROM information_schema.columns
                WHERE table_schema = current_schema() AND table_name = 'task_evidence' AND column_name = 'id'""",
                String.class)).isEqualTo("YES");
    }

    @Test
    void foreignKeyCascadesAndIndexIsComposite() {
        assertThat(jdbc.queryForObject("""
                SELECT confdeltype::text FROM pg_constraint
                WHERE conname = 'fk_task_evidence_task' AND conrelid = 'task_evidence'::regclass""", String.class))
                .isEqualTo("c");
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_indexdef(indexrelid) FROM pg_index
                WHERE indexrelid = 'idx_task_evidence_task_created_id'::regclass""", String.class))
                .contains("(task_id, created_at DESC, id DESC)");
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'chk_task_evidence_content_not_blank'""", String.class))
                .contains("btrim");
    }

    @Test
    void blankCheckRejectsAsciiBlankAtDatabaseLevel() {
        long taskId = newTask("check");
        // The failed statement aborts this transaction; nothing is queried afterwards in this test.
        assertRejected("INSERT INTO task_evidence (task_id, content, created_at) VALUES (?, '   ', now())",
                "23514", "chk_task_evidence_content_not_blank", null, taskId);
    }

    @Test
    void missingParentViolatesForeignKey() {
        long missing = ownMissingTaskId();
        // The failed statement aborts this transaction; nothing is queried afterwards in this test.
        assertRejected("INSERT INTO task_evidence (task_id, content, created_at) VALUES (?, 'a', now())",
                "23503", "fk_task_evidence_task", null, missing);
    }

    /**
     * Missing parent built from an own id: inserted, then deleted in this transaction, and its absence checked right
     * before use. No MAX(id)+constant (collision / overflow). Only this checked condition is claimed.
     */
    private long ownMissingTaskId() {
        long id = newTask("missing-probe");
        assertThat(jdbc.update("DELETE FROM tasks WHERE id = ?", id)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tasks WHERE id = ?", Long.class, id)).isZero();
        return id;
    }

    /** Exact rejection oracle; must be the LAST statement of a test (the outer transaction is then aborted). */
    private void assertRejected(String sql, String sqlState, String constraint, String column, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    Throwable cause = ((DataIntegrityViolationException) e).getMostSpecificCause();
                    assertThat(cause).isInstanceOf(org.postgresql.util.PSQLException.class);
                    org.postgresql.util.PSQLException pg = (org.postgresql.util.PSQLException) cause;
                    assertThat(pg.getSQLState()).isEqualTo(sqlState);
                    assertThat(pg.getServerErrorMessage()).isNotNull();
                    if (constraint != null) {
                        assertThat(pg.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
                    }
                    if (column != null) {
                        assertThat(pg.getServerErrorMessage().getColumn()).isEqualTo(column);
                    }
                });
    }

    @Test
    void notNullRejectsMissingTaskId() {
        assertRejected("INSERT INTO task_evidence (task_id, content, created_at) VALUES (NULL, 'a', now())",
                "23502", null, "task_id");
    }

    @Test
    void notNullRejectsMissingContent() {
        long taskId = newTask("nn-content");
        assertRejected("INSERT INTO task_evidence (task_id, content, created_at) VALUES (?, NULL, now())",
                "23502", null, "content", taskId);
    }

    @Test
    void notNullRejectsMissingCreatedAt() {
        long taskId = newTask("nn-created");
        assertRejected("INSERT INTO task_evidence (task_id, content, created_at) VALUES (?, 'a', NULL)",
                "23502", null, "created_at", taskId);
    }

    @Test
    void contentOf2000CharactersIsAcceptedBySql() {
        long taskId = newTask("len-2000");
        String content = "c".repeat(2000);
        Long id = jdbc.queryForObject(
                "INSERT INTO task_evidence (task_id, content, created_at) VALUES (?, ?, now()) RETURNING id",
                Long.class, taskId, content);
        assertThat(jdbc.queryForObject("SELECT char_length(content) FROM task_evidence WHERE id = ?", Integer.class, id))
                .isEqualTo(2000);
    }

    @Test
    void contentOf2001CharactersIsRejectedBySql() {
        long taskId = newTask("len-2001");
        // 22001 string_data_right_truncation carries no constraint name; SQLSTATE is the oracle.
        assertRejected("INSERT INTO task_evidence (task_id, content, created_at) VALUES (?, ?, now())",
                "22001", null, null, taskId, "c".repeat(2001));
    }

    @Test
    void mapsAndKeepsMicrosecondsAndUnicode() {
        long taskId = newTask("map");
        String content = "😀 line1\n\tline2";
        TaskEvidence saved = adapter.insertAndFlush(TaskEvidence.newEvidence(taskId, content,
                Instant.parse("2026-11-01T07:00:00.123456789Z")));
        entityManager.clear();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT task_id, content, to_char(created_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US') AS ts "
                        + "FROM task_evidence WHERE id = ?", saved.id());
        assertThat(((Number) row.get("task_id")).longValue()).isEqualTo(taskId);
        assertThat(row.get("content")).isEqualTo(content);
        assertThat(row.get("ts")).isEqualTo("2026-11-01T07:00:00.123456");
        assertThat(adapter.findPage(taskId, 0, 20).evidence()).containsExactly(saved);
    }

    @Test
    void ordersByCreatedAtDescThenIdDescAndPages() {
        long taskId = newTask("order");
        long other = newTask("other");
        TaskEvidence old = insert(taskId, "old", "2026-11-01T00:00:00Z");
        TaskEvidence tieA = insert(taskId, "tieA", "2026-11-01T01:00:00Z");
        TaskEvidence tieB = insert(taskId, "tieB", "2026-11-01T01:00:00Z");
        insert(other, "foreign", "2026-11-01T05:00:00Z");
        entityManager.clear();

        TaskEvidencePage first = adapter.findPage(taskId, 0, 2);
        assertThat(first.evidence()).extracting(TaskEvidence::id).containsExactly(tieB.id(), tieA.id());
        assertThat(first.hasNext()).isTrue();
        TaskEvidencePage second = adapter.findPage(taskId, 2, 2);
        assertThat(second.evidence()).extracting(TaskEvidence::id).containsExactly(old.id());
        assertThat(second.hasNext()).isFalse();
        TaskEvidencePage beyond = adapter.findPage(taskId, 4, 2);
        assertThat(beyond.evidence()).isEmpty();
        assertThat(beyond.hasNext()).isFalse();
    }

    @Test
    void deletingTaskCascadesOnlyItsEvidence() {
        long doomed = newTask("doomed");
        long kept = newTask("kept");
        TaskEvidence gone = insert(doomed, "x", "2026-11-01T00:00:00Z");
        TaskEvidence stays = insert(kept, "y", "2026-11-01T00:00:00Z");
        jdbc.update("DELETE FROM tasks WHERE id = ?", doomed);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_evidence WHERE id = ?", Long.class, gone.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_evidence WHERE id = ?", Long.class, stays.id())).isOne();
    }

    @Test
    void lockParentReportsExistenceAndLocalTimeoutIsFiveSeconds() {
        long taskId = newTask("lock");
        adapter.applyLocalLockTimeout();
        assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("5s");
        assertThat(adapter.lockParentTaskForKeyShare(taskId)).isTrue();
        long missing = ownMissingTaskId();
        assertThat(adapter.lockParentTaskForKeyShare(missing)).isFalse();
    }
}
