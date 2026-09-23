package io.github.jiangzhengxun.taskmanager.task.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.ListTasksUseCase;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;

@WebMvcTest(ListTasksController.class)
class ListTasksControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ListTasksUseCase listTasksUseCase;

    @Test
    void returnsOrderedTaskRepresentations() throws Exception {
        Task firstTask = new Task(
                101L,
                "First collection task",
                "Verify first response",
                TaskStatus.TODO,
                Instant.parse("2026-09-23T02:00:00Z"),
                Instant.parse("2026-09-23T02:00:00Z"));
        Task secondTask = new Task(
                102L,
                "Second collection task",
                "Verify second response",
                TaskStatus.TODO,
                Instant.parse("2026-09-23T02:01:00Z"),
                Instant.parse("2026-09-23T02:01:00Z"));

        when(listTasksUseCase.listTasks())
                .thenReturn(List.of(firstTask, secondTask));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$[0].id").value(101))
                .andExpect(jsonPath("$[0].title")
                        .value("First collection task"))
                .andExpect(jsonPath("$[0].description")
                        .value("Verify first response"))
                .andExpect(jsonPath("$[0].status").value("TODO"))
                .andExpect(jsonPath("$[0].createdAt")
                        .value("2026-09-23T02:00:00Z"))
                .andExpect(jsonPath("$[0].updatedAt")
                        .value("2026-09-23T02:00:00Z"))
                .andExpect(jsonPath("$[1].id").value(102))
                .andExpect(jsonPath("$[1].title")
                        .value("Second collection task"))
                .andExpect(jsonPath("$[1].description")
                        .value("Verify second response"))
                .andExpect(jsonPath("$[1].status").value("TODO"))
                .andExpect(jsonPath("$[1].createdAt")
                        .value("2026-09-23T02:01:00Z"))
                .andExpect(jsonPath("$[1].updatedAt")
                        .value("2026-09-23T02:01:00Z"));
    }

    @Test
    void returnsEmptyArrayWhenNoTasksExist() throws Exception {
        when(listTasksUseCase.listTasks())
                .thenReturn(List.of());

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.APPLICATION_JSON))
                .andExpect(content().json("[]"));
    }

    @Test
    void hidesInternalDetailsForUnexpectedFailure() throws Exception {
        when(listTasksUseCase.listTasks())
                .thenThrow(new RuntimeException(
                        "password=secret jdbc:postgresql://internal-db/tasks"));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isInternalServerError())
                .andExpect(content()
                        .contentTypeCompatibleWith(
                                MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error")
                        .value("Internal Server Error"))
                .andExpect(jsonPath("$.message")
                        .value("An unexpected error occurred"))
                .andExpect(jsonPath("$.message")
                        .value(not(containsString("secret"))))
                .andExpect(jsonPath("$.message")
                        .value(not(containsString(
                                "jdbc:postgresql"))))
                .andExpect(jsonPath("$.path")
                        .value("/api/tasks"));
    }
}
