package io.github.jiangzhengxun.taskmanager.task.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;
import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;

class GetTaskByIdServiceTest {

    @Test
    void returnsTaskWhenItExists() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        Task task = new Task(
                101L,
                "Read Task by ID",
                "Verify application lookup",
                TaskStatus.TODO,
                Instant.parse("2026-09-22T02:15:00Z"),
                Instant.parse("2026-09-22T02:15:00Z"));

        when(taskRepository.findById(101L))
                .thenReturn(Optional.of(task));

        GetTaskByIdService service =
                new GetTaskByIdService(taskRepository);

        Task result = service.getById(101L);

        assertThat(result).isEqualTo(task);
        verify(taskRepository).findById(101L);
    }

    @Test
    void throwsTaskNotFoundExceptionWhenTaskDoesNotExist() {
        TaskRepository taskRepository = mock(TaskRepository.class);

        when(taskRepository.findById(404L))
                .thenReturn(Optional.empty());

        GetTaskByIdService service =
                new GetTaskByIdService(taskRepository);

        assertThatThrownBy(() -> service.getById(404L))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessage("Task not found: 404");

        verify(taskRepository).findById(404L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsNonPositiveIdBeforeRepositoryLookup(long id) {
        TaskRepository taskRepository = mock(TaskRepository.class);
        GetTaskByIdService service =
                new GetTaskByIdService(taskRepository);

        assertThatThrownBy(() -> service.getById(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("id must be positive");

        verifyNoInteractions(taskRepository);
    }
}
