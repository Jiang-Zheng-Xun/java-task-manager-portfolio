package io.github.jiangzhengxun.taskmanager.task.application.service;

import java.time.Clock;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;
import io.github.jiangzhengxun.taskmanager.task.application.port.in.ReplaceTaskUseCase;
import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;

@Service
public class ReplaceTaskService implements ReplaceTaskUseCase {

    private final TaskRepository repository;
    private final Clock clock;

    public ReplaceTaskService(TaskRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public Task replaceTask(
            long id, String title, String description, TaskStatus status) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        Objects.requireNonNull(status, "status must not be null");

        Task existing = repository.findById(id)
                .orElseThrow(() -> new TaskNotFoundException(id));
        Task replacement = existing.replaceEditableFields(
                title, description, status, clock.instant());

        return replacement == existing
                ? existing
                : repository.save(replacement);
    }
}
