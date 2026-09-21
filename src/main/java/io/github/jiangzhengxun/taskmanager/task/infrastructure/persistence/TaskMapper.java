package io.github.jiangzhengxun.taskmanager.task.infrastructure.persistence;

import java.util.Objects;

import org.springframework.stereotype.Component;

import io.github.jiangzhengxun.taskmanager.task.domain.Task;

@Component
public class TaskMapper {

    public TaskEntity toEntity(Task task) {
        Objects.requireNonNull(task, "task must not be null");

        return new TaskEntity(
                task.id(),
                task.title(),
                task.description(),
                task.status(),
                task.createdAt(),
                task.updatedAt());
    }

    public Task toDomain(TaskEntity entity) {
        Objects.requireNonNull(entity, "entity must not be null");

        return new Task(
                entity.getId(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
