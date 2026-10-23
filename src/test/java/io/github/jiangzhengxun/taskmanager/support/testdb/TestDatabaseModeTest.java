package io.github.jiangzhengxun.taskmanager.support.testdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TestDatabaseModeTest {

    private static final String EXPECTED_MESSAGE = "CONFIG: invalid TEST_DB_MODE";

    @Test
    void parseAcceptsLowercaseLocal() {
        assertEquals(TestDatabaseMode.LOCAL, TestDatabaseMode.parse("local"));
    }

    @Test
    void parseAcceptsLowercaseCi() {
        assertEquals(TestDatabaseMode.CI, TestDatabaseMode.parse("ci"));
    }

    static Stream<Arguments> invalidModes() {
        return Stream.of(
                Arguments.of("null", null),
                Arguments.of("empty string", ""),
                Arguments.of("single space", " "),
                Arguments.of("tab", "\t"),
                Arguments.of("newline", "\n"),
                Arguments.of("uppercase LOCAL", "LOCAL"),
                Arguments.of("uppercase CI", "CI"),
                Arguments.of("title case Local", "Local"),
                Arguments.of("title case Ci", "Ci"),
                Arguments.of("leading space before local", " local"),
                Arguments.of("trailing space after local", "local "),
                Arguments.of("leading space before ci", " ci"),
                Arguments.of("trailing space after ci", "ci "),
                Arguments.of("unknown value test", "test"),
                Arguments.of("unknown value prod", "prod"),
                Arguments.of("local followed by newline", "local\n"),
                Arguments.of("ci followed by tab", "ci\t"),
                Arguments.of("local followed by NUL", "local\u0000"));
    }

    @ParameterizedTest(name = "[{index}] rejects {0}")
    @MethodSource("invalidModes")
    void parseRejectsInvalidMode(String label, String raw) {
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> TestDatabaseMode.parse(raw),
                () -> "expected rejection for case: " + label);
        assertEquals(EXPECTED_MESSAGE, thrown.getMessage(),
                () -> "unexpected message for case: " + label);
    }
}
