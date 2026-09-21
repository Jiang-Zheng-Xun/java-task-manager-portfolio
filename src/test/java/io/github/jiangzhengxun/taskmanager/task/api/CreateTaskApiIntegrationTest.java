package io.github.jiangzhengxun.taskmanager.task.api;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class CreateTaskApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsTaskAndPersistsItInPostgreSql() throws Exception {
        long rowCountBefore = taskCount();

        MvcResult result = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "  Prepare portfolio README  ",
                                  "description": "  Add API examples  "
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.title")
                        .value("Prepare portfolio README"))
                .andExpect(jsonPath("$.description")
                        .value("Add API examples"))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists())
                .andReturn();

        JsonNode responseBody = objectMapper.readTree(
                result.getResponse().getContentAsString());

        long createdId = responseBody.get("id").asLong();

        assertThat(taskCount()).isEqualTo(rowCountBefore + 1);

        Map<String, Object> persistedTask = jdbcTemplate.queryForMap(
            """
            SELECT title, description, status, created_at, updated_at
            FROM tasks
            WHERE id = ?
            """,
            createdId);

        assertThat(persistedTask.get("title"))
            .isEqualTo("Prepare portfolio README");
        assertThat(persistedTask.get("description"))
            .isEqualTo("Add API examples");
        assertThat(persistedTask.get("status")).isEqualTo("TODO");
        assertThat(persistedTask.get("created_at")).isNotNull();
        assertThat(persistedTask.get("updated_at")).isNotNull();
    }

    @Test
    void rejectsBlankTitleWithoutCreatingRow() throws Exception {
        assertRejectedWithoutCreatingRow("""
                {
                  "title": "   ",
                  "description": "Add API examples"
                }
                """);
    }

    @Test
    void rejectsOverlongTitleWithoutCreatingRow() throws Exception {
        String overlongTitle = "a".repeat(201);

        assertRejectedWithoutCreatingRow("""
                {
                  "title": "%s",
                  "description": "Add API examples"
                }
                """.formatted(overlongTitle));
    }

    @Test
    void rejectsOverlongDescriptionWithoutCreatingRow() throws Exception {
        String overlongDescription = "b".repeat(2001);

        assertRejectedWithoutCreatingRow("""
                {
                  "title": "Prepare portfolio README",
                  "description": "%s"
                }
                """.formatted(overlongDescription));
    }

    @Test
    void rejectsUnsupportedFieldsWithoutCreatingRow() throws Exception {
        assertRejectedWithoutCreatingRow("""
                {
                  "title": "Prepare portfolio README",
                  "description": "Add API examples",
                  "id": 101,
                  "status": "COMPLETED"
                }
                """);
    }

    private void assertRejectedWithoutCreatingRow(String requestBody)
            throws Exception {
        long rowCountBefore = taskCount();

        MvcResult result = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/tasks"))
                .andReturn();


assertThat(taskCount()).isEqualTo(rowCountBefore);
        String responseBody = result.getResponse().getContentAsString();

        assertThat(responseBody)
                .doesNotContain(
                        "org.springframework",
                        "org.hibernate",
                        "postgresql://",
                        "jdbc:",
                        "password",
                        "stackTrace");
    }

    private long taskCount() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tasks",
                Long.class);

        if (count == null) {
            throw new IllegalStateException(
                    "Task count query returned null");
        }

        return count;
    }
}
