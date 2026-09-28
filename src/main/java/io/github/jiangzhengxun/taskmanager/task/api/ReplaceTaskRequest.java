package io.github.jiangzhengxun.taskmanager.task.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ReplaceTaskRequest(
        @NotBlank(message = "title must not be blank")
        String title,
        @NotNull(message = "description must be present")
        JsonNode description,
        @NotNull(message = "status must not be null")
        TaskStatus status) {

    public String descriptionValue() {
        if (description.isNull()) {
            return null;
        }
        if (!description.isTextual()) {
            throw new IllegalArgumentException(
                    "description must be a string or null");
        }
        return description.asText();
    }
}
