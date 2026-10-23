package io.github.jiangzhengxun.taskmanager.support.testdb;

/**
 * Parses the raw TEST_DB_MODE value supplied by the caller.
 * Accepts exactly "local" or "ci"; everything else is rejected.
 */
public enum TestDatabaseMode {
    LOCAL,
    CI;

    private static final String INVALID_MESSAGE = "CONFIG: invalid TEST_DB_MODE";

    public static TestDatabaseMode parse(String raw) {
        if ("local".equals(raw)) {
            return LOCAL;
        }
        if ("ci".equals(raw)) {
            return CI;
        }
        throw new IllegalArgumentException(INVALID_MESSAGE);
    }
}
