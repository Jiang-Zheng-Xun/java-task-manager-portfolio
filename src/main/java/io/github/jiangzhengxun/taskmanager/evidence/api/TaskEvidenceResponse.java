package io.github.jiangzhengxun.taskmanager.evidence.api;

import io.github.jiangzhengxun.taskmanager.evidence.domain.TaskEvidence;
import java.time.Instant;

public record TaskEvidenceResponse(long id, long taskId, String content, Instant createdAt) {

    static TaskEvidenceResponse from(TaskEvidence evidence) {
        return new TaskEvidenceResponse(evidence.id(), evidence.taskId(), evidence.content(), evidence.createdAt());
    }
}
