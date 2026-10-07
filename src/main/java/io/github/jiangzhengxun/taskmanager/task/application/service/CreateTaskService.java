package io.github.jiangzhengxun.taskmanager.task.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.CreateTaskCommand;
import io.github.jiangzhengxun.taskmanager.task.application.port.in.CreateTaskUseCase;
import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;

@Service
public class CreateTaskService implements CreateTaskUseCase {

    private final TaskRepository taskRepository;
    private final Clock clock;

    public CreateTaskService(
            TaskRepository taskRepository,
            Clock clock) {
        this.taskRepository = Objects.requireNonNull(
                taskRepository,
                "taskRepository must not be null");
        this.clock = Objects.requireNonNull(
                clock,
                "clock must not be null");
    }

    @Override
    @Transactional
    public Task create(CreateTaskCommand command) {
        Objects.requireNonNull(command, "command must not be null");

        Instant now = clock.instant();

        Task task = Task.create(
                command.title(),
                command.description(),
                now);

        return taskRepository.save(task);
    }
}
