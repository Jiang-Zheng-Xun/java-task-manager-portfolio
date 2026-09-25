package io.github.jiangzhengxun.taskmanager.task.application.service;

import java.time.Clock;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;
import io.github.jiangzhengxun.taskmanager.task.application.port.in.UpdateTaskStatusUseCase;
import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;

@Service
public class UpdateTaskStatusService implements UpdateTaskStatusUseCase {

    private final TaskRepository repository;
    private final Clock clock;

    public UpdateTaskStatusService(TaskRepository repository, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional
    public Task updateStatus(long id, TaskStatus status) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        Objects.requireNonNull(status, "status must not be null");

        Task existing = repository.findById(id)
                .orElseThrow(() -> new TaskNotFoundException(id));
        Task changed = existing.withStatus(status, clock.instant());

        return changed == existing ? existing : repository.save(changed);
    }
}
