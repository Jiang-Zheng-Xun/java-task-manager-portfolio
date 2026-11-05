package io.github.jiangzhengxun.taskmanager.support.testdb;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure validator that checks a test database settings snapshot against an independent expected-target policy.
 *
 * <p>A successful result only means that the supplied configuration matches the selected policy target. It never
 * grants permission to connect. String representations of all nested types are redacted and never expose raw
 * field values.
 */
public final class TestDatabaseConfigValidator {

    private static final String URL_PREFIX = "jdbc:postgresql://";
    private static final int MAX_HOST_LENGTH = 253;
    private static final int MAX_LABEL_LENGTH = 63;
    private static final int IPV4_OCTET_COUNT = 4;
    private static final int MAX_OCTET_DIGITS = 3;
    private static final int MAX_OCTET = 255;
    private static final int MAX_PORT_DIGITS = 5;
    private static final int MAX_PORT = 65535;

    private TestDatabaseConfigValidator() {
    }

    /** Fixed error categories, declared in evaluation priority order. */
    public enum ErrorCategory {
        MODE_REJECTED,
        POLICY_MISSING,
        POLICY_INVALID,
        CONFIG_MISSING,
        URL_UNSUPPORTED,
        TARGET_MISMATCH
    }

    /** Actual settings to validate. All fields are nullable. */
    public static final class Snapshot {
        private final String mode;
        private final String jdbcUrl;
        private final String username;

        public Snapshot(String mode, String jdbcUrl, String username) {
            this.mode = mode;
            this.jdbcUrl = jdbcUrl;
            this.username = username;
        }

        public String mode() {
            return mode;
        }

        public String jdbcUrl() {
            return jdbcUrl;
        }

        public String username() {
            return username;
        }

        @Override
        public String toString() {
            return "Snapshot[redacted]";
        }
    }

    /** Expected target for one mode. Fields are nullable here and checked during validation. */
    public static final class ExpectedTarget {
        private final String jdbcUrl;
        private final String username;

        public ExpectedTarget(String jdbcUrl, String username) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
        }

        public String jdbcUrl() {
            return jdbcUrl;
        }

        public String username() {
            return username;
        }

        @Override
        public String toString() {
            return "ExpectedTarget[redacted]";
        }
    }

    /** Validation outcome: success, or exactly one error category. */
    public static final class Result {
        private static final Result SUCCESS = new Result(null);

        private final ErrorCategory error;

        private Result(ErrorCategory error) {
            this.error = error;
        }

        static Result success() {
            return SUCCESS;
        }

        static Result failure(ErrorCategory error) {
            return new Result(Objects.requireNonNull(error, "error"));
        }

        public boolean isSuccess() {
            return error == null;
        }

        public Optional<ErrorCategory> error() {
            return Optional.ofNullable(error);
        }

        @Override
        public String toString() {
            return error == null ? "Result[SUCCESS]" : "Result[" + error.name() + "]";
        }
    }

    /**
     * Validates the snapshot against the target the policy defines for the snapshot's mode.
     *
     * <p>Success means the configuration matches; it is never permission to connect.
     */
    public static Result validate(Snapshot snapshot, Map<TestDatabaseMode, ExpectedTarget> policy) {
        String rawMode = snapshot == null ? null : snapshot.mode();
        TestDatabaseMode mode;
        try {
            mode = TestDatabaseMode.parse(rawMode);
        } catch (IllegalArgumentException rejected) {
            return Result.failure(ErrorCategory.MODE_REJECTED);
        }
        if (snapshot == null) {
            // Unreachable while TestDatabaseMode.parse rejects null; kept so a null snapshot can never pass.
            return Result.failure(ErrorCategory.MODE_REJECTED);
        }

        if (policy == null) {
            return Result.failure(ErrorCategory.POLICY_MISSING);
        }
        ExpectedTarget expected = policy.get(mode);
        if (expected == null) {
            return Result.failure(ErrorCategory.POLICY_MISSING);
        }

        String expectedUrl = expected.jdbcUrl();
        String expectedUsername = expected.username();
        if (isNullOrEmpty(expectedUrl) || isNullOrEmpty(expectedUsername) || !isSupportedUrl(expectedUrl)) {
            return Result.failure(ErrorCategory.POLICY_INVALID);
        }

        String actualUrl = snapshot.jdbcUrl();
        String actualUsername = snapshot.username();
        if (isNullOrEmpty(actualUrl) || isNullOrEmpty(actualUsername)) {
            return Result.failure(ErrorCategory.CONFIG_MISSING);
        }

        if (!isSupportedUrl(actualUrl)) {
            return Result.failure(ErrorCategory.URL_UNSUPPORTED);
        }

        if (!actualUrl.equals(expectedUrl) || !actualUsername.equals(expectedUsername)) {
            return Result.failure(ErrorCategory.TARGET_MISMATCH);
        }
        return Result.success();
    }

    private static boolean isNullOrEmpty(String value) {
        return value == null || value.isEmpty();
    }

    /** Accepts exactly {@code jdbc:postgresql://host:port/database} under the narrow lexical contract. */
    private static boolean isSupportedUrl(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)) {
            return false;
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            // Printable ASCII only: rejects whitespace, control characters and all non-ASCII input.
            if (c <= 0x20 || c >= 0x7F) {
                return false;
            }
        }
        String remainder = url.substring(URL_PREFIX.length());
        int slash = remainder.indexOf('/');
        if (slash < 0) {
            return false;
        }
        String authority = remainder.substring(0, slash);
        int colon = authority.indexOf(':');
        if (colon < 0) {
            return false;
        }
        return isHost(authority.substring(0, colon))
                && isPort(authority.substring(colon + 1))
                && isDatabase(remainder.substring(slash + 1));
    }

    private static boolean isHost(String host) {
        if (host.isEmpty() || host.length() > MAX_HOST_LENGTH) {
            return false;
        }
        boolean digitsAndDotsOnly = true;
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!isAsciiDigit(c) && c != '.') {
                digitsAndDotsOnly = false;
                break;
            }
        }
        // Numeric-looking hosts must be valid IPv4 and never fall back to DNS name rules.
        return digitsAndDotsOnly ? isIpv4(host) : isDnsName(host);
    }

    private static boolean isIpv4(String host) {
        String[] octets = host.split("[.]", -1);
        if (octets.length != IPV4_OCTET_COUNT) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > MAX_OCTET_DIGITS) {
                return false;
            }
            if (octet.length() > 1 && octet.charAt(0) == '0') {
                return false;
            }
            if (Integer.parseInt(octet) > MAX_OCTET) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDnsName(String host) {
        for (String label : host.split("[.]", -1)) {
            if (label.isEmpty() || label.length() > MAX_LABEL_LENGTH) {
                return false;
            }
            if (!isAsciiAlphanumeric(label.charAt(0)) || !isAsciiAlphanumeric(label.charAt(label.length() - 1))) {
                return false;
            }
            for (int i = 0; i < label.length(); i++) {
                char c = label.charAt(i);
                if (!isAsciiAlphanumeric(c) && c != '-') {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isPort(String port) {
        if (port.isEmpty() || port.length() > MAX_PORT_DIGITS || port.charAt(0) == '0') {
            return false;
        }
        for (int i = 0; i < port.length(); i++) {
            if (!isAsciiDigit(port.charAt(i))) {
                return false;
            }
        }
        return Integer.parseInt(port) <= MAX_PORT;
    }

    private static boolean isDatabase(String database) {
        if (database.isEmpty()) {
            return false;
        }
        for (int i = 0; i < database.length(); i++) {
            char c = database.charAt(i);
            if (!isAsciiAlphanumeric(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isAsciiAlphanumeric(char c) {
        return isAsciiDigit(c) || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }
}
