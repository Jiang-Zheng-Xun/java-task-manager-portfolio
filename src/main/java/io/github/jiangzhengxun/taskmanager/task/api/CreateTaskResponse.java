package io.github.jiangzhengxun.taskmanager.task.api;

import java.time.Instant;

import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;

public record CreateTaskResponse(
        Long id,
        String title,
        String description,
        TaskStatus status,
        Instant createdAt,
        Instant updatedAt) {

    public static CreateTaskResponse from(Task task) {
        return new CreateTaskResponse(
                task.id(),
                task.title(),
                task.description(),
                task.status(),
                task.createdAt(),
                task.updatedAt());
    }
}
