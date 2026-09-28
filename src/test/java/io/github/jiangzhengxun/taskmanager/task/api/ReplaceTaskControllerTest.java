package io.github.jiangzhengxun.taskmanager.task.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;
import io.github.jiangzhengxun.taskmanager.task.application.port.in.ReplaceTaskUseCase;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;

@WebMvcTest(ReplaceTaskController.class)
class ReplaceTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReplaceTaskUseCase useCase;

    @Test
    void replacesTaskAndReturnsCompleteRepresentation() throws Exception {
        Instant created = Instant.parse("2026-09-28T01:00:00Z");
        Instant updated = created.plusSeconds(60);
        Task result = new Task(
                7L, "New title", "New description",
                TaskStatus.COMPLETED, created, updated);
        when(useCase.replaceTask(
                7L, "New title", "New description", TaskStatus.COMPLETED))
                .thenReturn(result);

        mockMvc.perform(put("/api/tasks/{id}", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"New title",
                                 "description":"New description",
                                 "status":"COMPLETED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.title").value("New title"))
                .andExpect(jsonPath("$.description").value("New description"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.createdAt")
                        .value("2026-09-28T01:00:00Z"))
                .andExpect(jsonPath("$.updatedAt")
                        .value("2026-09-28T01:01:00Z"));
    }

    @Test
    void explicitNullDescriptionClearsIt() throws Exception {
        Instant now = Instant.parse("2026-09-28T01:00:00Z");
        Task result = new Task(
                7L, "Task", null, TaskStatus.TODO, now, now);
        when(useCase.replaceTask(7L, "Task", null, TaskStatus.TODO))
                .thenReturn(result);

        mockMvc.perform(put("/api/tasks/{id}", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Task",
                                 "description":null,
                                 "status":"TODO"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value(
                        org.hamcrest.Matchers.nullValue()));

        verify(useCase).replaceTask(7L, "Task", null, TaskStatus.TODO);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"title\":\"Task\",\"status\":\"TODO\"}",
            "{\"title\":\" \",\"description\":null,\"status\":\"TODO\"}",
            "{\"title\":\"Task\",\"description\":5,\"status\":\"TODO\"}",
            "{\"title\":\"Task\",\"description\":null,\"status\":\"INVALID\"}",
            "{\"title\":\"Task\",\"description\":null,\"status\":\"TODO\",\"id\":7}",
            "{\"title\":\"Task\",\"description\":null,\"status\":\"TODO\",\"createdAt\":\"2026-09-28T01:00:00Z\"}"
    })
    void rejectsInvalidRequestsBeforeUseCase(String body) throws Exception {
        mockMvc.perform(put("/api/tasks/{id}", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        verifyNoInteractions(useCase);
    }

    @Test
    void returnsNotFoundForMissingTask() throws Exception {
        when(useCase.replaceTask(404L, "Task", null, TaskStatus.TODO))
                .thenThrow(new TaskNotFoundException(404L));

        mockMvc.perform(put("/api/tasks/{id}", 404L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Task","description":null,"status":"TODO"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void hidesUnexpectedFailure() throws Exception {
        when(useCase.replaceTask(7L, "Task", null, TaskStatus.TODO))
                .thenThrow(new RuntimeException("password=secret"));

        mockMvc.perform(put("/api/tasks/{id}", 7L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Task","description":null,"status":"TODO"}
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value("An unexpected error occurred"));
    }
}
