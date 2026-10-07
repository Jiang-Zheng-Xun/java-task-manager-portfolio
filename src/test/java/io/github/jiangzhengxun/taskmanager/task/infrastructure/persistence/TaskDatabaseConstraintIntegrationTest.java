package io.github.jiangzhengxun.taskmanager.task.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class TaskDatabaseConstraintIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void acceptsMaximumLengthTitleAndDescription() {
        String title = "T".repeat(200);
        String description = "D".repeat(2000);
        Long id = jdbcTemplate.queryForObject(
                """
                INSERT INTO tasks (title, description, status)
                VALUES (?, ?, ?)
                RETURNING id
                """,
                Long.class,
                title, description, "TODO");

        try {
            Integer count = jdbcTemplate.queryForObject(
                    """
                    SELECT count(*) FROM tasks
                    WHERE id = ? AND title = ? AND description = ?
                      AND status = 'TODO'
                      AND created_at IS NOT NULL AND updated_at IS NOT NULL
                    """,
                    Integer.class,
                    id, title, description);
            assertThat(count).isEqualTo(1);
        } finally {
            jdbcTemplate.update("DELETE FROM tasks WHERE id = ?", id);
        }
    }

    @Test
    void rejectsBlankTitle() {
        assertRejected(
                "INSERT INTO tasks (title, status) VALUES ('   ', 'TODO')",
                "23514");
    }

    @Test
    void rejectsUnsupportedStatus() {
        assertRejected(
                "INSERT INTO tasks (title, status) VALUES ('Valid', 'BLOCKED')",
                "23514");
    }

    @Test
    void rejectsNullTitle() {
        assertRejected(
                "INSERT INTO tasks (title, status) VALUES (NULL, 'TODO')",
                "23502");
    }

    @Test
    void rejectsNullStatus() {
        assertRejected(
                "INSERT INTO tasks (title, status) VALUES ('Valid', NULL)",
                "23502");
    }

    @Test
    void rejectsNullCreatedAt() {
        assertRejected(
                """
                INSERT INTO tasks (title, status, created_at)
                VALUES ('Valid', 'TODO', NULL)
                """,
                "23502");
    }

    @Test
    void rejectsNullUpdatedAt() {
        assertRejected(
                """
                INSERT INTO tasks (title, status, updated_at)
                VALUES ('Valid', 'TODO', NULL)
                """,
                "23502");
    }

    @Test
    void rejectsTitleLongerThanColumnLimit() {
        assertRejected(
                "INSERT INTO tasks (title, status) VALUES (?, 'TODO')",
                "22001",
                "T".repeat(201));
    }

    @Test
    void rejectsDescriptionLongerThanColumnLimit() {
        assertRejected(
                """
                INSERT INTO tasks (title, description, status)
                VALUES ('Valid', ?, 'TODO')
                """,
                "22001",
                "D".repeat(2001));
    }

    private void assertRejected(String sql, String expectedSqlState,
                                Object... arguments) {
        DataIntegrityViolationException exception = assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(sql, arguments));

        assertThat(exception.getMostSpecificCause())
                .isInstanceOf(PSQLException.class);
        PSQLException postgresException =
                (PSQLException) exception.getMostSpecificCause();
        assertThat(postgresException.getSQLState())
                .isEqualTo(expectedSqlState);
    }
}
