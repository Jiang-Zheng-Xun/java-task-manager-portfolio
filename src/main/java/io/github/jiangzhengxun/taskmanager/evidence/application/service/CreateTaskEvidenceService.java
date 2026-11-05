package io.github.jiangzhengxun.taskmanager.evidence.application.service;

import io.github.jiangzhengxun.taskmanager.evidence.application.port.in.CreateTaskEvidenceUseCase;
import io.github.jiangzhengxun.taskmanager.evidence.application.port.out.TaskEvidenceRepository;
import io.github.jiangzhengxun.taskmanager.evidence.domain.TaskEvidence;
import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateTaskEvidenceService implements CreateTaskEvidenceUseCase {

    private final TaskEvidenceRepository evidenceRepository;
    private final Clock clock;

    public CreateTaskEvidenceService(TaskEvidenceRepository evidenceRepository, Clock clock) {
        this.evidenceRepository = Objects.requireNonNull(evidenceRepository, "evidenceRepository must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    @Transactional
    public TaskEvidence createEvidence(long taskId, String rawContent) {
        // All input validation (taskId, content) happens before any repository call.
        EvidenceInputValidation.requirePositiveTaskId(taskId);
        TaskEvidence evidence = TaskEvidence.newEvidence(taskId, rawContent, clock.instant());
        evidenceRepository.applyLocalLockTimeout();
        if (!evidenceRepository.lockParentTaskForKeyShare(taskId)) {
            throw new TaskNotFoundException(taskId);
        }
        return evidenceRepository.insertAndFlush(evidence);
    }
}
