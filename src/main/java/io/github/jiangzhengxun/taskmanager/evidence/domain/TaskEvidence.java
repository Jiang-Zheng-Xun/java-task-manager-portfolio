package io.github.jiangzhengxun.taskmanager.evidence.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Evidence attached to a task. Every construction path (new, restored, canonical) enforces the content
 * rules via {@link EvidenceContent#normalize(String)} and truncates createdAt to microseconds.
 * {@code id == null} means not yet persisted; a persisted id must be positive.
 */
public record TaskEvidence(Long id, long taskId, String content, Instant createdAt) {

    public TaskEvidence {
        if (id != null && id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (taskId <= 0) {
            throw new IllegalArgumentException("taskId must be positive");
        }
        content = EvidenceContent.normalize(content);
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
    }

    public static TaskEvidence newEvidence(long taskId, String rawContent, Instant createdAt) {
        return new TaskEvidence(null, taskId, rawContent, createdAt);
    }

    public static TaskEvidence restore(long id, long taskId, String content, Instant createdAt) {
        return new TaskEvidence(id, taskId, content, createdAt);
    }

    public boolean isPersisted() {
        return id != null;
    }
}
