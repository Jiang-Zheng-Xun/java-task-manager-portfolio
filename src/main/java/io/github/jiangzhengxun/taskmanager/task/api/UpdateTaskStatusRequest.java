package io.github.jiangzhengxun.taskmanager.task.api;

import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateTaskStatusRequest(
        @NotNull(message = "status must not be null")
        TaskStatus status) {
}
