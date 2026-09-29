package io.github.jiangzhengxun.taskmanager.task.application.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;
import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;

class DeleteTaskServiceTest {

    @Test
    void deletesExistingTask() {
        TaskRepository repository = mock(TaskRepository.class);
        when(repository.deleteById(7L)).thenReturn(true);

        new DeleteTaskService(repository).deleteTask(7L);

        verify(repository).deleteById(7L);
    }

    @Test
    void missingTaskThrowsNotFound() {
        TaskRepository repository = mock(TaskRepository.class);
        when(repository.deleteById(7L)).thenReturn(false);

        assertThatThrownBy(
                () -> new DeleteTaskService(repository).deleteTask(7L))
                .isInstanceOf(TaskNotFoundException.class);

        verify(repository).deleteById(7L);
    }

    @Test
    void nonPositiveIdFailsBeforeRepositoryAccess() {
        TaskRepository repository = mock(TaskRepository.class);

        assertThatThrownBy(
                () -> new DeleteTaskService(repository).deleteTask(0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("id must be positive");

        verifyNoInteractions(repository);
    }
}
