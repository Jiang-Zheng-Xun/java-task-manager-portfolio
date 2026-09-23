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

class ListTasksServiceTest {

    @Test
    void returnsTasksInRepositoryOrder() {
        TaskRepository taskRepository = mock(TaskRepository.class);
        Task firstTask = new Task(
                101L,
                "First collection task",
                "Verify first result",
                TaskStatus.TODO,
                Instant.parse("2026-09-23T02:00:00Z"),
                Instant.parse("2026-09-23T02:00:00Z"));
        Task secondTask = new Task(
                102L,
                "Second collection task",
                "Verify second result",
                TaskStatus.TODO,
                Instant.parse("2026-09-23T02:01:00Z"),
                Instant.parse("2026-09-23T02:01:00Z"));

        when(taskRepository.findAllByIdAscending())
                .thenReturn(List.of(firstTask, secondTask));

        ListTasksService service =
                new ListTasksService(taskRepository);

        List<Task> result = service.listTasks();

        assertThat(result).containsExactly(firstTask, secondTask);
        verify(taskRepository).findAllByIdAscending();
    }

    @Test
    void returnsEmptyListWhenRepositoryIsEmpty() {
        TaskRepository taskRepository = mock(TaskRepository.class);

        when(taskRepository.findAllByIdAscending())
                .thenReturn(List.of());

        ListTasksService service =
                new ListTasksService(taskRepository);

        assertThat(service.listTasks()).isEmpty();
        verify(taskRepository).findAllByIdAscending();
    }
}
