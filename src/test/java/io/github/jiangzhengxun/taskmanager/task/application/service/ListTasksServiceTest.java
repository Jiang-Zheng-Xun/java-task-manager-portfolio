package io.github.jiangzhengxun.taskmanager.task.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;
import io.github.jiangzhengxun.taskmanager.task.application.model.TaskPage;

class ListTasksServiceTest {

    @Test
    void returnsRepositoryPageWithoutSortingAgain() {
        TaskRepository repository = mock(TaskRepository.class);
        Task first = new Task(
                101L, "First", null, TaskStatus.TODO,
                Instant.parse("2026-09-24T01:00:00Z"),
                Instant.parse("2026-09-24T01:00:00Z"));
        Task second = new Task(
                102L, "Second", null, TaskStatus.TODO,
                Instant.parse("2026-09-24T01:01:00Z"),
                Instant.parse("2026-09-24T01:01:00Z"));
        TaskPage expected = new TaskPage(List.of(first, second), true);
        when(repository.findPageByIdAscending(0, 2)).thenReturn(expected);

        ListTasksService service = new ListTasksService(repository);
        TaskPage actual = service.listTasks(0, 2);

        assertThat(actual).isEqualTo(expected);
        verify(repository).findPageByIdAscending(0, 2);
    }

    @Test
    void returnsEmptyPageFromRepository() {
        TaskRepository repository = mock(TaskRepository.class);
        TaskPage expected = new TaskPage(List.of(), false);
        when(repository.findPageByIdAscending(3, 2)).thenReturn(expected);

        ListTasksService service = new ListTasksService(repository);
        assertThat(service.listTasks(3, 2)).isEqualTo(expected);
        verify(repository).findPageByIdAscending(3, 2);
    }
}
