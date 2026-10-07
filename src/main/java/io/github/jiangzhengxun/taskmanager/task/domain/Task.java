package io.github.jiangzhengxun.taskmanager.task.domain;

import java.time.Instant;
import java.util.Objects;

public record Task(
        Long id,
        String title,
        String description,
        TaskStatus status,
        Instant createdAt,
        Instant updatedAt
) {

    public Task {
        title = normalizeTitle(title);
        description = normalizeDescription(description);
        status = Objects.requireNonNull(status, "status must not be null");
        createdAt = Objects.requireNonNull(
                createdAt,
                "createdAt must not be null"
        );
        updatedAt = Objects.requireNonNull(
                updatedAt,
                "updatedAt must not be null"
        );
    }

    public static Task create(
            String title,
            String description,
            Instant now
    ) {
        Objects.requireNonNull(now, "now must not be null");

        return new Task(
                null,
                title,
                description,
                TaskStatus.TODO,
                now,
                now
        );
    }

    public Task withStatus(TaskStatus newStatus, Instant now) {
        Objects.requireNonNull(newStatus, "status must not be null");
        Objects.requireNonNull(now, "now must not be null");

        if (status == newStatus) {
            return this;
        }

        return new Task(
                id,
                title,
                description,
                newStatus,
                createdAt,
                now
        );
    }

    public Task replaceEditableFields(
            String newTitle,
            String newDescription,
            TaskStatus newStatus,
            Instant now) {
        Objects.requireNonNull(newStatus, "status must not be null");
        Objects.requireNonNull(now, "now must not be null");

        String normalizedTitle = normalizeTitle(newTitle);
        String normalizedDescription = normalizeDescription(newDescription);

        if (title.equals(normalizedTitle)
                && Objects.equals(description, normalizedDescription)
                && status == newStatus) {
            return this;
        }

        return new Task(
                id,
                normalizedTitle,
                normalizedDescription,
                newStatus,
                createdAt,
                now
        );
    }

    private static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException(
                    "title must not be blank"
            );
        }

        String normalizedTitle = title.strip();

        if (normalizedTitle.length() > 200) {
            throw new IllegalArgumentException(
                    "title must not exceed 200 characters"
            );
        }

        return normalizedTitle;
    }

    private static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }

        String normalizedDescription = description.strip();

        if (normalizedDescription.isEmpty()) {
            return null;
        }

        if (normalizedDescription.length() > 2000) {
            throw new IllegalArgumentException(
                    "description must not exceed 2000 characters"
            );
        }

        return normalizedDescription;
    }
}
