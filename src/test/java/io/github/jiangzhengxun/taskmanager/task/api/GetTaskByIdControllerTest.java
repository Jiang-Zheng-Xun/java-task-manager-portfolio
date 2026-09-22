package io.github.jiangzhengxun.taskmanager.task.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.GetTaskByIdUseCase;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;
import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;

@WebMvcTest(GetTaskByIdController.class)
class GetTaskByIdControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GetTaskByIdUseCase getTaskByIdUseCase;

    @Test
    void returnsTaskRepresentationWhenTaskExists() throws Exception {
        Instant now = Instant.parse("2026-09-22T03:15:00Z");
        Task task = new Task(
                101L,
                "Read Task by ID",
                "Verify GET response",
                TaskStatus.TODO,
                now,
                now);

        when(getTaskByIdUseCase.getById(101L))
                .thenReturn(task);

        mockMvc.perform(get("/api/tasks/{id}", 101L))
                .andExpect(status().isOk())
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(101))
                .andExpect(jsonPath("$.title").value("Read Task by ID"))
                .andExpect(jsonPath("$.description")
                        .value("Verify GET response"))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.createdAt")
                        .value("2026-09-22T03:15:00Z"))
                .andExpect(jsonPath("$.updatedAt")
                        .value("2026-09-22T03:15:00Z"));
    }

    @Test
    void returnsNotFoundWhenTaskDoesNotExist() throws Exception {
        when(getTaskByIdUseCase.getById(404L))
                .thenThrow(new TaskNotFoundException(404L));

        mockMvc.perform(get("/api/tasks/{id}", 404L))
                .andExpect(status().isNotFound())
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message")
                        .value("Task not found: 404"))
                .andExpect(jsonPath("$.path")
                        .value("/api/tasks/404"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void returnsBadRequestForNonPositiveId(long id) throws Exception {
        when(getTaskByIdUseCase.getById(id))
                .thenThrow(new IllegalArgumentException(
                        "id must be positive"));

        mockMvc.perform(get("/api/tasks/{id}", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message")
                        .value("id must be positive"))
                .andExpect(jsonPath("$.path")
                        .value("/api/tasks/" + id));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-number",
            "9223372036854775808"
    })
    void returnsBadRequestForUnparseableId(String id)
            throws Exception {
        mockMvc.perform(get("/api/tasks/{id}", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message")
                        .value("id must be a valid integer"))
                .andExpect(jsonPath("$.path")
                        .value("/api/tasks/" + id));

        verifyNoInteractions(getTaskByIdUseCase);
    }

    @Test
    void hidesInternalDetailsForUnexpectedFailure() throws Exception {
        when(getTaskByIdUseCase.getById(101L))
                .thenThrow(new RuntimeException(
                        "password=secret jdbc:postgresql://internal-db/tasks"));

        mockMvc.perform(get("/api/tasks/{id}", 101L))
                .andExpect(status().isInternalServerError())
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error")
                        .value("Internal Server Error"))
                .andExpect(jsonPath("$.message")
                        .value("An unexpected error occurred"))
                .andExpect(jsonPath("$.path")
                        .value("/api/tasks/101"));
    }
}
