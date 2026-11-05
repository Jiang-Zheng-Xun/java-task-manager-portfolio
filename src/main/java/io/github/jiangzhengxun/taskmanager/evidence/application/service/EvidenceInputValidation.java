package io.github.jiangzhengxun.taskmanager.evidence.application.service;

final class EvidenceInputValidation {

    static final String ID_MESSAGE = "id must be positive";
    static final String PAGINATION_MESSAGE = "page must be non-negative and size must be between 1 and 100";

    private EvidenceInputValidation() {
    }

    static void requirePositiveTaskId(long taskId) {
        if (taskId <= 0) {
            throw new IllegalArgumentException(ID_MESSAGE);
        }
    }

    static void requireValidPage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException(PAGINATION_MESSAGE);
        }
    }
}
