package io.github.jiangzhengxun.taskmanager.task.application.service;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;
import io.github.jiangzhengxun.taskmanager.task.application.port.in.DeleteTaskUseCase;
import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;

@Service
public class DeleteTaskService implements DeleteTaskUseCase {

    private final TaskRepository repository;

    public DeleteTaskService(TaskRepository repository) {
        this.repository = Objects.requireNonNull(
                repository, "repository must not be null");
    }

    @Override
    @Transactional
    public void deleteTask(long id) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }

        if (!repository.deleteById(id)) {
            throw new TaskNotFoundException(id);
        }
    }
}
