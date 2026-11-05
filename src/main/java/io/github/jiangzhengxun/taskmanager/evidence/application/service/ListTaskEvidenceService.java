package io.github.jiangzhengxun.taskmanager.evidence.application.service;

import io.github.jiangzhengxun.taskmanager.evidence.application.port.in.ListTaskEvidenceUseCase;
import io.github.jiangzhengxun.taskmanager.evidence.application.port.out.TaskEvidencePage;
import io.github.jiangzhengxun.taskmanager.evidence.application.port.out.TaskEvidenceRepository;
import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;
import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ListTaskEvidenceService implements ListTaskEvidenceUseCase {

    private final TaskRepository taskRepository;
    private final TaskEvidenceRepository evidenceRepository;

    public ListTaskEvidenceService(TaskRepository taskRepository, TaskEvidenceRepository evidenceRepository) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        this.evidenceRepository = Objects.requireNonNull(evidenceRepository, "evidenceRepository must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public TaskEvidencePage listEvidence(long taskId, int page, int size) {
        EvidenceInputValidation.requirePositiveTaskId(taskId);
        EvidenceInputValidation.requireValidPage(page, size);
        if (taskRepository.findById(taskId).isEmpty()) {
            throw new TaskNotFoundException(taskId);
        }
        long offset = (long) page * (long) size;
        if (offset > Integer.MAX_VALUE) {
            return TaskEvidencePage.empty();
        }
        return evidenceRepository.findPage(taskId, offset, size);
    }
}
