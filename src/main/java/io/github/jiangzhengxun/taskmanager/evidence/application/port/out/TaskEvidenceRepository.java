package io.github.jiangzhengxun.taskmanager.evidence.application.port.out;

import io.github.jiangzhengxun.taskmanager.evidence.domain.TaskEvidence;

public interface TaskEvidenceRepository {

    /** Sets transaction-local lock_timeout (5000ms) for the current transaction only. */
    void applyLocalLockTimeout();

    /** SELECT ... FOR KEY SHARE on the parent task; false when it does not exist. */
    boolean lockParentTaskForKeyShare(long taskId);

    /** Inserts and flushes; integrity / lock errors propagate unchanged. */
    TaskEvidence insertAndFlush(TaskEvidence evidence);

    TaskEvidencePage findPage(long taskId, long offset, int size);
}
