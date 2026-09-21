package io.github.jiangzhengxun.taskmanager.task.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.CreateTaskCommand;
import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;

class CreateTaskServiceTest {

    @Test
    void createsAndSavesTaskUsingCurrentUtcInstant() {
        Instant now = Instant.parse("2026-09-21T08:30:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        TaskRepository taskRepository = mock(TaskRepository.class);

        when(taskRepository.save(any(Task.class)))
                .thenAnswer(invocation -> {
                    Task task = invocation.getArgument(0);

                    return new Task(
                            101L,
                            task.title(),
                            task.description(),
                            task.status(),
                            task.createdAt(),
                            task.updatedAt());
                });

        CreateTaskService service =
                new CreateTaskService(taskRepository, clock);

        Task result = service.create(new CreateTaskCommand(
                "  Prepare portfolio README  ",
                "  Add API examples  "));

        ArgumentCaptor<Task> taskCaptor =
                ArgumentCaptor.forClass(Task.class);
        verify(taskRepository).save(taskCaptor.capture());

        Task taskPassedToRepository = taskCaptor.getValue();

        assertThat(taskPassedToRepository.id()).isNull();
        assertThat(taskPassedToRepository.title())
                .isEqualTo("Prepare portfolio README");
        assertThat(taskPassedToRepository.description())
                .isEqualTo("Add API examples");
        assertThat(taskPassedToRepository.status())
                .isEqualTo(TaskStatus.TODO);
        assertThat(taskPassedToRepository.createdAt()).isEqualTo(now);
        assertThat(taskPassedToRepository.updatedAt()).isEqualTo(now);

        assertThat(result.id()).isEqualTo(101L);
        assertThat(result.title())
                .isEqualTo("Prepare portfolio README");
        assertThat(result.description())
                .isEqualTo("Add API examples");
        assertThat(result.status()).isEqualTo(TaskStatus.TODO);
        assertThat(result.createdAt()).isEqualTo(now);
        assertThat(result.updatedAt()).isEqualTo(now);
    }
}
