package io.github.jiangzhengxun.taskmanager.evidence.application.port.in;

import io.github.jiangzhengxun.taskmanager.evidence.domain.TaskEvidence;

public interface CreateTaskEvidenceUseCase {

    TaskEvidence createEvidence(long taskId, String rawContent);
}
