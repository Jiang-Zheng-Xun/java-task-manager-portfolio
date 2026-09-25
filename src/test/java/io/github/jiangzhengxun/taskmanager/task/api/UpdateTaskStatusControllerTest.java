package io.github.jiangzhengxun.taskmanager.task.api;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.UpdateTaskStatusUseCase;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;
import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;

@WebMvcTest(UpdateTaskStatusController.class)
class UpdateTaskStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UpdateTaskStatusUseCase useCase;

    @Test
    void returnsUpdatedTask() throws Exception {
        Instant created = Instant.parse("2026-09-25T01:00:00Z");
        Instant updated = created.plusSeconds(60);
        Task result = new Task(7L, "Task", null,
                TaskStatus.IN_PROGRESS, created, updated);
        when(useCase.updateStatus(7L, TaskStatus.IN_PROGRESS))
                .thenReturn(result);

        mockMvc.perform(patch("/api/tasks/{id}/status", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.createdAt")
                        .value("2026-09-25T01:00:00Z"))
                .andExpect(jsonPath("$.updatedAt")
                        .value("2026-09-25T01:01:00Z"));
    }

    @Test
    void rejectsMissingStatusBeforeUseCase() throws Exception {
        mockMvc.perform(patch("/api/tasks/{id}/status", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path")
                        .value("/api/tasks/7/status"));

        verifyNoInteractions(useCase);
    }

    @Test
    void returnsNotFoundWhenTaskDoesNotExist() throws Exception {
        when(useCase.updateStatus(404L, TaskStatus.COMPLETED))
                .thenThrow(new TaskNotFoundException(404L));

        mockMvc.perform(patch("/api/tasks/{id}/status", 404L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path")
                        .value("/api/tasks/404/status"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"status\":\"INVALID\"}",
            "{\"status\":\"TODO\",\"unexpected\":true}"
    })
    void rejectsInvalidOrUnknownFields(String body) throws Exception {
        mockMvc.perform(patch("/api/tasks/{id}/status", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path")
                        .value("/api/tasks/7/status"));

        verifyNoInteractions(useCase);
    }

    @Test
    void rejectsNonPositiveId() throws Exception {
        when(useCase.updateStatus(0L, TaskStatus.TODO))
                .thenThrow(new IllegalArgumentException("id must be positive"));

        mockMvc.perform(patch("/api/tasks/{id}/status", 0L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TODO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void rejectsNonNumericIdBeforeUseCase() throws Exception {
        mockMvc.perform(patch("/api/tasks/{id}/status", "abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TODO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("id must be a valid integer"));

        verifyNoInteractions(useCase);
    }

    @Test
    void hidesInternalFailureDetails() throws Exception {
        when(useCase.updateStatus(7L, TaskStatus.TODO))
                .thenThrow(new RuntimeException(
                        "password=secret jdbc:postgresql://internal-db/tasks"));

        mockMvc.perform(patch("/api/tasks/{id}/status", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"TODO\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value("An unexpected error occurred"))
                .andExpect(jsonPath("$.path")
                        .value("/api/tasks/7/status"));
    }
}
