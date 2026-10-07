package io.github.jiangzhengxun.taskmanager.task.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;
import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;

class ReplaceTaskServiceTest {

    private static final Instant CREATED =
            Instant.parse("2026-09-28T01:00:00Z");
    private static final Instant NOW =
            Instant.parse("2026-09-28T02:00:00Z");
    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void replacesEditableFieldsAndSaves() {
        TaskRepository repository = mock(TaskRepository.class);
        Task original = new Task(
                7L, "Old", "Old description",
                TaskStatus.TODO, CREATED, CREATED);
        Task replacement = original.replaceEditableFields(
                "New", null, TaskStatus.COMPLETED, NOW);
        when(repository.findById(7L)).thenReturn(Optional.of(original));
        when(repository.save(replacement)).thenReturn(replacement);

        Task result = new ReplaceTaskService(repository, CLOCK)
                .replaceTask(7L, "New", null, TaskStatus.COMPLETED);

        assertThat(result).isEqualTo(replacement);
        verify(repository).save(replacement);
    }

    @Test
    void normalizedEquivalentReplacementSkipsSave() {
        TaskRepository repository = mock(TaskRepository.class);
        Task original = new Task(
                7L, "Task", null,
                TaskStatus.IN_PROGRESS, CREATED, CREATED);
        when(repository.findById(7L)).thenReturn(Optional.of(original));

        Task result = new ReplaceTaskService(repository, CLOCK)
                .replaceTask(7L, "  Task  ", "   ",
                        TaskStatus.IN_PROGRESS);

        assertThat(result).isSameAs(original);
        assertThat(result.updatedAt()).isEqualTo(CREATED);
        verify(repository, never()).save(any(Task.class));
    }

    @Test
    void missingTaskThrowsNotFoundAndDoesNotSave() {
        TaskRepository repository = mock(TaskRepository.class);
        when(repository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new ReplaceTaskService(repository, CLOCK)
                .replaceTask(7L, "Task", null, TaskStatus.TODO))
                .isInstanceOf(TaskNotFoundException.class);
        verify(repository, never()).save(any(Task.class));
    }

    @Test
    void nonPositiveIdFailsBeforeRepositoryAccess() {
        TaskRepository repository = mock(TaskRepository.class);

        assertThatThrownBy(() -> new ReplaceTaskService(repository, CLOCK)
                .replaceTask(0L, "Task", null, TaskStatus.TODO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("id must be positive");
        org.mockito.Mockito.verifyNoInteractions(repository);
    }
}
