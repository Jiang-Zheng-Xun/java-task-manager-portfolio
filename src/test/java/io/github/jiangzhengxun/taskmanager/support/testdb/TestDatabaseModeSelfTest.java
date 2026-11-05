package io.github.jiangzhengxun.taskmanager.support.testdb;

/**
 * Standalone self-test for {@link TestDatabaseMode#parse(String)}.
 * Uses explicit checks so it cannot be disabled like Java assert.
 */
public final class TestDatabaseModeSelfTest {

    private static final String EXPECTED_MESSAGE = "CONFIG: invalid TEST_DB_MODE";

    private static final String[] INVALID_INPUTS = {
        null,
        "",
        " ",
        "\t",
        "\n",
        "LOCAL",
        "CI",
        "Local",
        "Ci",
        " local",
        "local ",
        " ci",
        "ci ",
        "test",
        "prod",
        "local\n",
        "ci\t",
        "local\u0000"
    };

    private TestDatabaseModeSelfTest() {
    }

    public static void main(String[] args) {
        expectValid("local", TestDatabaseMode.LOCAL);
        expectValid("ci", TestDatabaseMode.CI);
        for (int i = 0; i < INVALID_INPUTS.length; i++) {
            expectInvalid(i, INVALID_INPUTS[i]);
        }
        System.out.println("MODE_SELF_TEST_OK");
    }

    private static void expectValid(String raw, TestDatabaseMode expected) {
        TestDatabaseMode actual = TestDatabaseMode.parse(raw);
        if (actual != expected) {
            throw new AssertionError("valid case '" + raw + "' returned " + actual);
        }
    }

    private static void expectInvalid(int index, String raw) {
        try {
            TestDatabaseMode actual = TestDatabaseMode.parse(raw);
            throw new AssertionError("invalid case #" + index + " returned " + actual);
        } catch (IllegalArgumentException e) {
            if (!EXPECTED_MESSAGE.equals(e.getMessage())) {
                throw new AssertionError("invalid case #" + index + " had wrong message");
            }
        }
    }
}
