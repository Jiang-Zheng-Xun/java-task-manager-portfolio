package io.github.jiangzhengxun.taskmanager.evidence.application.port.in;

import io.github.jiangzhengxun.taskmanager.evidence.application.port.out.TaskEvidencePage;

public interface ListTaskEvidenceUseCase {

    TaskEvidencePage listEvidence(long taskId, int page, int size);
}
