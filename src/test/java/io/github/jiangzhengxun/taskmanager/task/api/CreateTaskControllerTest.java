package io.github.jiangzhengxun.taskmanager.task.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.CreateTaskCommand;
import io.github.jiangzhengxun.taskmanager.task.application.port.in.CreateTaskUseCase;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;

@WebMvcTest(CreateTaskController.class)
class CreateTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateTaskUseCase createTaskUseCase;

    @Test
    void createsTaskAndReturnsFullRepresentation() throws Exception {
        Instant now = Instant.parse("2026-09-21T09:15:00Z");

        Task createdTask = new Task(
                101L,
                "Prepare portfolio README",
                "Add API examples",
                TaskStatus.TODO,
                now,
                now);

        when(createTaskUseCase.create(any(CreateTaskCommand.class)))
                .thenReturn(createdTask);

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Prepare portfolio README",
                                  "description": "Add API examples"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.id").value(101))
                .andExpect(jsonPath("$.title")
                        .value("Prepare portfolio README"))
                .andExpect(jsonPath("$.description")
                        .value("Add API examples"))
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.createdAt")
                        .value("2026-09-21T09:15:00Z"))
                .andExpect(jsonPath("$.updatedAt")
                        .value("2026-09-21T09:15:00Z"));
    }

    @Test
    void rejectsBlankTitleWithSafeBadRequestResponse() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "   ",
                                    "description": "Add API examples"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message",
                        not(blankOrNullString())))
                .andExpect(jsonPath("$.path").value("/api/tasks"));

        verifyNoInteractions(createTaskUseCase);
    }

    @Test
    void rejectsMalformedJsonWithSafeBadRequestResponse() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "Prepare portfolio README",
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message",
                        not(blankOrNullString())))
                .andExpect(jsonPath("$.path").value("/api/tasks"));

        verifyNoInteractions(createTaskUseCase);
    }

    @Test
    void returnsBadRequestForDomainValidationFailure() throws Exception {
        when(createTaskUseCase.create(any(CreateTaskCommand.class)))
                .thenThrow(new IllegalArgumentException(
                        "title must not be blank"));

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "Invalid domain input",
                                    "description": "Add API examples"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message")
                        .value("title must not be blank"))
                .andExpect(jsonPath("$.path").value("/api/tasks"));
    }

    @Test
    void rejectsUnsupportedFieldsWithoutCallingUseCase() throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "Prepare portfolio README",
                                    "description": "Add API examples",
                                    "id": 101,
                                    "status": "COMPLETED"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message")
                        .value("Malformed JSON request"))
                .andExpect(jsonPath("$.path").value("/api/tasks"));

        verifyNoInteractions(createTaskUseCase);
    }

    @Test
    void hidesInternalDetailsForUnexpectedFailure() throws Exception {
        when(createTaskUseCase.create(any(CreateTaskCommand.class)))
                .thenThrow(new RuntimeException(
                        "password=secret jdbc:postgresql://internal-db/tasks"));

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "title": "Prepare portfolio README",
                                    "description": "Add API examples"
                                }
                                """))
                .andExpect(status().isInternalServerError())
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error")
                        .value("Internal Server Error"))
                .andExpect(jsonPath("$.message")
                        .value("An unexpected error occurred"))
                .andExpect(jsonPath("$.message")
                        .value(not(org.hamcrest.Matchers.containsString(
                                "secret"))))
                .andExpect(jsonPath("$.message")
                        .value(not(org.hamcrest.Matchers.containsString(
                                "jdbc:postgresql"))))
                .andExpect(jsonPath("$.path").value("/api/tasks"));
    }
}
