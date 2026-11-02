package io.github.jiangzhengxun.taskmanager.evidence.infrastructure.persistence;

import io.github.jiangzhengxun.taskmanager.evidence.domain.TaskEvidence;

final class TaskEvidenceMapper {

    private TaskEvidenceMapper() {
    }

    static TaskEvidenceEntity toEntity(TaskEvidence evidence) {
        return new TaskEvidenceEntity(evidence.id(), evidence.taskId(), evidence.content(), evidence.createdAt());
    }

    static TaskEvidence toDomain(TaskEvidenceEntity entity) {
        return TaskEvidence.restore(entity.getId(), entity.getTaskId(), entity.getContent(), entity.getCreatedAt());
    }
}
