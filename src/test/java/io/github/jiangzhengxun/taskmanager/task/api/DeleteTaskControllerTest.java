package io.github.jiangzhengxun.taskmanager.task.api;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;
import io.github.jiangzhengxun.taskmanager.task.application.port.in.DeleteTaskUseCase;

@WebMvcTest(DeleteTaskController.class)
class DeleteTaskControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeleteTaskUseCase useCase;

    @Test
    void deletesExistingTaskWithNoResponseBody() throws Exception {
        mockMvc.perform(delete("/api/tasks/{id}", 7L))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(useCase).deleteTask(7L);
    }

    @Test
    void returnsNotFoundForMissingOrAlreadyDeletedTask() throws Exception {
        doThrow(new TaskNotFoundException(7L))
                .when(useCase).deleteTask(7L);

        mockMvc.perform(delete("/api/tasks/{id}", 7L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").value("/api/tasks/7"));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsNonPositiveId(long id) throws Exception {
        doThrow(new IllegalArgumentException("id must be positive"))
                .when(useCase).deleteTask(id);

        mockMvc.perform(delete("/api/tasks/{id}", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-number",
            "9223372036854775808"
    })
    void rejectsUnparseableIdBeforeUseCase(String id) throws Exception {
        mockMvc.perform(delete("/api/tasks/{id}", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("id must be a valid integer"));

        verifyNoInteractions(useCase);
    }

    @Test
    void hidesUnexpectedFailure() throws Exception {
        doThrow(new RuntimeException("password=secret"))
                .when(useCase).deleteTask(7L);

        mockMvc.perform(delete("/api/tasks/{id}", 7L))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message")
                        .value("An unexpected error occurred"));
    }
}
