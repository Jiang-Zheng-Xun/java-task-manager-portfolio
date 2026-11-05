package io.github.jiangzhengxun.taskmanager.evidence.application.port.out;

import io.github.jiangzhengxun.taskmanager.evidence.domain.TaskEvidence;
import java.util.List;

public record TaskEvidencePage(List<TaskEvidence> evidence, boolean hasNext) {

    public TaskEvidencePage {
        evidence = List.copyOf(evidence);
    }

    public static TaskEvidencePage empty() {
        return new TaskEvidencePage(List.of(), false);
    }
}
