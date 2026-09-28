package io.github.jiangzhengxun.taskmanager.task.api;

import java.util.Map;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Duration;

import jakarta.persistence.EntityManager;

import static org.hamcrest.Matchers.hasSize;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional
class TaskApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

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

        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("/api/tasks/" + createdId);

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

    @Test
    void readsCreatedTaskFromPostgreSqlById() throws Exception {
        MvcResult createResult = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Read Task by ID",
                                  "description": "Verify full GET path"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createResponse = objectMapper.readTree(
                createResult.getResponse().getContentAsString());

        long createdId = createResponse.get("id").asLong();

        String location = createResult.getResponse()
                .getHeader("Location");

        assertThat(location)
            .isEqualTo("/api/tasks/" + createdId);

        mockMvc.perform(get(location))
                .andExpect(status().isOk())
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(createdId))
                .andExpect(jsonPath("$.title")
                        .value("Read Task by ID"))
                .andExpect(jsonPath("$.description")
                        .value("Verify full GET path"))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void returnsNotFoundForMissingTaskId() throws Exception {
        mockMvc.perform(get("/api/tasks/{id}", Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message")
                        .value("Task not found: " + Long.MAX_VALUE))
                .andExpect(jsonPath("$.path")
                        .value("/api/tasks/" + Long.MAX_VALUE));
    }

    @Test
    void returnsEmptyCollectionFromPostgreSql() throws Exception {
        jdbcTemplate.update("DELETE FROM tasks");

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next-Page", "false"))
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("[]"));
    }

    @Test
    void returnsCreatedTasksInAscendingIdOrder() throws Exception {
        jdbcTemplate.update("DELETE FROM tasks");

        MvcResult firstCreateResult = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "First collection task",
                                  "description": "Verify first API result"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult secondCreateResult = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Second collection task",
                                  "description": "Verify second API result"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        long firstId = objectMapper.readTree(
                firstCreateResult.getResponse().getContentAsString())
                .get("id")
                .asLong();
        long secondId = objectMapper.readTree(
                secondCreateResult.getResponse().getContentAsString())
                .get("id")
                .asLong();

        assertThat(firstId).isLessThan(secondId);

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(firstId))
                .andExpect(jsonPath("$[0].title")
                        .value("First collection task"))
                .andExpect(jsonPath("$[0].description")
                        .value("Verify first API result"))
                .andExpect(jsonPath("$[0].status").value("TODO"))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[0].updatedAt").exists())
                .andExpect(jsonPath("$[1].id").value(secondId))
                .andExpect(jsonPath("$[1].title")
                        .value("Second collection task"))
                .andExpect(jsonPath("$[1].description")
                        .value("Verify second API result"))
                .andExpect(jsonPath("$[1].status").value("TODO"))
                .andExpect(jsonPath("$[1].createdAt").exists())
                .andExpect(jsonPath("$[1].updatedAt").exists());
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

    @Test
    void pagesCreatedTasksThroughPostgreSql() throws Exception {
        jdbcTemplate.update("DELETE FROM tasks");

        long[] ids = new long[3];
        for (int index = 0; index < 3; index++) {
            MvcResult created = mockMvc.perform(post("/api/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"Paged task %d"}
                                    """.formatted(index + 1)))
                    .andExpect(status().isCreated())
                    .andReturn();

            ids[index] = objectMapper.readTree(
                    created.getResponse().getContentAsString())
                    .get("id").asLong();
        }

        assertThat(ids[0]).isLessThan(ids[1]);
        assertThat(ids[1]).isLessThan(ids[2]);

        mockMvc.perform(get("/api/tasks")
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next-Page", "true"))
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(ids[0]))
                .andExpect(jsonPath("$[1].id").value(ids[1]))
                .andExpect(jsonPath("$[0].title").value("Paged task 1"))
                .andExpect(jsonPath("$[1].title").value("Paged task 2"));

        mockMvc.perform(get("/api/tasks")
                    .param("page", "1")
                    .param("size", "2"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Has-Next-Page", "false"))
            .andExpect(jsonPath("$", hasSize(1)))
            .andExpect(jsonPath("$[0].id").value(ids[2]))
            .andExpect(jsonPath("$[0].title").value("Paged task 3"));

        mockMvc.perform(get("/api/tasks")
                        .param("page", "2")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next-Page", "false"))
                .andExpect(content().json("[]"));
    }

    @Test
    void limitsDefaultCollectionPageToTwentyTasks() throws Exception {
        jdbcTemplate.update("DELETE FROM tasks");

        for (int index = 0; index < 21; index++) {
            mockMvc.perform(post("/api/tasks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"Default page task %d"}
                                    """.formatted(index + 1)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next-Page", "true"))
                .andExpect(jsonPath("$", hasSize(20)))
                .andExpect(jsonPath("$[0].title")
                        .value("Default page task 1"))
                .andExpect(jsonPath("$[19].title")
                        .value("Default page task 20"));

        mockMvc.perform(get("/api/tasks").param("page", "1"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next-Page", "false"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title")
                        .value("Default page task 21"));

        mockMvc.perform(get("/api/tasks").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next-Page", "false"))
                .andExpect(jsonPath("$", hasSize(21)));
    }

    @Test
    void updatesStatusThroughHttpAndPostgreSqlWithoutChangingTimestampOnReplay()
            throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Update status path\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createdBody = objectMapper.readTree(
                created.getResponse().getContentAsString());
        long id = createdBody.get("id").asLong();
        String createdAt = createdBody.get("createdAt").asText();

        MvcResult changed = mockMvc.perform(
                        patch("/api/tasks/{id}/status", id)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.createdAt").value(createdAt))
                .andReturn();

        String updatedAt = objectMapper.readTree(
                changed.getResponse().getContentAsString())
                .get("updatedAt").asText();

        entityManager.flush();

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM tasks WHERE id = ?",
    String.class, id)).isEqualTo("IN_PROGRESS");

        Instant storedUpdatedAt = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM tasks WHERE id = ?",
    Timestamp.class, id).toInstant();
        assertThat(Duration.between(
                storedUpdatedAt, Instant.parse(updatedAt)).abs())
                .isLessThanOrEqualTo(Duration.ofNanos(1_000));

        mockMvc.perform(patch("/api/tasks/{id}/status", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").value(updatedAt));

        assertThat(jdbcTemplate.queryForObject(
            "SELECT status FROM tasks WHERE id = ?",
    String.class, id)).isEqualTo("IN_PROGRESS");

        Instant storedAfterReplay = jdbcTemplate.queryForObject(
            "SELECT updated_at FROM tasks WHERE id = ?",
    Timestamp.class, id).toInstant();
        assertThat(storedAfterReplay).isEqualTo(storedUpdatedAt);
    }

    @Test
    void replacesTaskThroughHttpAndPostgreSqlAndPreservesTimestampOnReplay()
            throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Original","description":"Before"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createdBody = objectMapper.readTree(
                created.getResponse().getContentAsString());
        long id = createdBody.get("id").asLong();
        String createdAt = createdBody.get("createdAt").asText();

        String replacement = """
                {"title":"Replaced","description":null,"status":"COMPLETED"}
                """;

        entityManager.flush();
        Instant storedCreatedAtBeforePut = jdbcTemplate.queryForObject(
        "SELECT created_at FROM tasks WHERE id = ?",
        Timestamp.class, id).toInstant();

        MvcResult updated = mockMvc.perform(put("/api/tasks/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("Replaced"))
                .andExpect(jsonPath("$.description")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.createdAt").value(createdAt))
                .andReturn();

        String updatedAt = objectMapper.readTree(
                updated.getResponse().getContentAsString())
                .get("updatedAt").asText();

        entityManager.flush();

        Map<String, Object> stored = jdbcTemplate.queryForMap(
                """
                SELECT title, description, status, created_at, updated_at
                FROM tasks WHERE id = ?
                """,
                id);
        assertThat(stored.get("title")).isEqualTo("Replaced");
        assertThat(stored.get("description")).isNull();
        assertThat(stored.get("status")).isEqualTo("COMPLETED");
        Instant storedCreatedAtAfterPut =
        ((Timestamp) stored.get("created_at")).toInstant();

        assertThat(storedCreatedAtAfterPut)
            .isEqualTo(storedCreatedAtBeforePut);
        assertThat(Duration.between(
            storedCreatedAtAfterPut, Instant.parse(createdAt)).abs())
            .isLessThanOrEqualTo(Duration.ofNanos(1_000));

        Instant storedUpdatedAt =
                ((Timestamp) stored.get("updated_at")).toInstant();
        assertThat(Duration.between(
                storedUpdatedAt, Instant.parse(updatedAt)).abs())
                .isLessThanOrEqualTo(Duration.ofNanos(1_000));

        mockMvc.perform(put("/api/tasks/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(replacement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedAt").value(updatedAt));

        entityManager.flush();
        Instant afterReplay = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM tasks WHERE id = ?",
                Timestamp.class, id).toInstant();
        assertThat(afterReplay).isEqualTo(storedUpdatedAt);

        mockMvc.perform(get("/api/tasks/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Replaced"))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void invalidPutDoesNotChangeStoredTask() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Unchanged","description":"Keep"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        long id = objectMapper.readTree(
                created.getResponse().getContentAsString())
                .get("id").asLong();

        entityManager.flush();
        Map<String, Object> before = jdbcTemplate.queryForMap(
                "SELECT title, description, status, updated_at FROM tasks WHERE id = ?",
                id);

        mockMvc.perform(put("/api/tasks/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Should not replace","status":"COMPLETED"}
                                """))
                .andExpect(status().isBadRequest());

        entityManager.flush();
        Map<String, Object> after = jdbcTemplate.queryForMap(
                "SELECT title, description, status, updated_at FROM tasks WHERE id = ?",
                id);
        assertThat(after).isEqualTo(before);
    }
}
