package io.github.jiangzhengxun.taskmanager.task.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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

class UpdateTaskStatusServiceTest {

    private static final Instant CREATED =
            Instant.parse("2026-09-25T01:00:00Z");
    private static final Instant NOW =
            Instant.parse("2026-09-25T03:00:00Z");
    private static final Clock CLOCK =
            Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void changesStatusAndSavesTask() {
        TaskRepository repository = mock(TaskRepository.class);
        Task original = task(TaskStatus.TODO);
        Task changed = original.withStatus(TaskStatus.IN_PROGRESS, NOW);
        when(repository.findById(7L)).thenReturn(Optional.of(original));
        when(repository.save(changed)).thenReturn(changed);

        Task result = new UpdateTaskStatusService(repository, CLOCK)
                .updateStatus(7L, TaskStatus.IN_PROGRESS);

        assertThat(result).isEqualTo(changed);
        verify(repository).save(changed);
    }

    @Test
    void sameStatusReturnsExistingTaskWithoutSave() {
        TaskRepository repository = mock(TaskRepository.class);
        Task original = task(TaskStatus.IN_PROGRESS);
        when(repository.findById(7L)).thenReturn(Optional.of(original));

        Task result = new UpdateTaskStatusService(repository, CLOCK)
                .updateStatus(7L, TaskStatus.IN_PROGRESS);

        assertThat(result).isSameAs(original);
        verify(repository).findById(7L);
        verifyNoInteractionsWithSave(repository);
    }

    @Test
    void missingTaskThrowsNotFound() {
        TaskRepository repository = mock(TaskRepository.class);
        when(repository.findById(7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new UpdateTaskStatusService(repository, CLOCK)
                .updateStatus(7L, TaskStatus.COMPLETED))
                .isInstanceOf(TaskNotFoundException.class);
    }

    private static Task task(TaskStatus status) {
        return new Task(7L, "Task", null, status, CREATED, CREATED);
    }

    private static void verifyNoInteractionsWithSave(TaskRepository repository) {
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never())
                .save(org.mockito.ArgumentMatchers.any(Task.class));
    }
}
