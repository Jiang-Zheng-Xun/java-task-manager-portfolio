package io.github.jiangzhengxun.taskmanager.task.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        @NotBlank(message = "title must not be blank")
        @Size(max = 200, message = "title must not exceed 200 characters")
        String title,

        @Size(max = 2000,
                message = "description must not exceed 2000 characters")
        String description) {
}
