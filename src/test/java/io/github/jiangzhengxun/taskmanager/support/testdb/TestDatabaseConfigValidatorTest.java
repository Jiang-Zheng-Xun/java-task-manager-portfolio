package io.github.jiangzhengxun.taskmanager.support.testdb;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseConfigValidator.ErrorCategory;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseConfigValidator.ExpectedTarget;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseConfigValidator.Result;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseConfigValidator.Snapshot;
import java.util.AbstractMap;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class TestDatabaseConfigValidatorTest {

    // Synthetic fixtures only; none of these hosts or credentials refer to a real database.
    private static final String LOCAL_HOST = "db-local.example.test";
    private static final String LOCAL_PORT = "15432";
    private static final String LOCAL_DATABASE = "tm_local_fixture";
    private static final String LOCAL_URL = "jdbc:postgresql://" + LOCAL_HOST + ":" + LOCAL_PORT + "/" + LOCAL_DATABASE;
    private static final String LOCAL_USER = "fixture_local_user";
    private static final String CI_HOST = "db-ci.example.test";
    private static final String CI_PORT = "25432";
    private static final String CI_DATABASE = "tm_ci_fixture";
    private static final String CI_URL = "jdbc:postgresql://" + CI_HOST + ":" + CI_PORT + "/" + CI_DATABASE;
    private static final String CI_USER = "fixture_ci_user";

    private static Map<TestDatabaseMode, ExpectedTarget> validPolicy() {
        Map<TestDatabaseMode, ExpectedTarget> policy = new EnumMap<>(TestDatabaseMode.class);
        policy.put(TestDatabaseMode.LOCAL, new ExpectedTarget(LOCAL_URL, LOCAL_USER));
        policy.put(TestDatabaseMode.CI, new ExpectedTarget(CI_URL, CI_USER));
        return policy;
    }

    private static Map<TestDatabaseMode, ExpectedTarget> localPolicy(String url, String username) {
        Map<TestDatabaseMode, ExpectedTarget> policy = new EnumMap<>(TestDatabaseMode.class);
        policy.put(TestDatabaseMode.LOCAL, new ExpectedTarget(url, username));
        return policy;
    }

    private static Result validate(String mode, String url, String username,
            Map<TestDatabaseMode, ExpectedTarget> policy) {
        return TestDatabaseConfigValidator.validate(new Snapshot(mode, url, username), policy);
    }

    private static void assertSuccess(Result result) {
        assertTrue(result.isSuccess(), () -> "expected success but was " + result);
        assertEquals(Optional.empty(), result.error());
    }

    private static void assertError(ErrorCategory expected, Result result) {
        assertFalse(result.isSuccess(), () -> "expected " + expected + " but was " + result);
        assertEquals(Optional.of(expected), result.error());
    }

    /** A supported URL must be accepted both as a policy target and as the matching actual URL. */
    private static void assertUrlAccepted(String url) {
        assertSuccess(validate("local", url, LOCAL_USER, localPolicy(url, LOCAL_USER)));
    }

    /**
     * An unsupported URL must make the policy invalid, and as an actual URL against a valid policy it must be
     * reported as unsupported.
     */
    private static void assertUrlRejected(String url) {
        assertError(ErrorCategory.POLICY_INVALID, validate("local", LOCAL_URL, LOCAL_USER, localPolicy(url, LOCAL_USER)));
        assertError(ErrorCategory.URL_UNSUPPORTED, validate("local", url, LOCAL_USER, validPolicy()));
    }

    private static void assertAllAccepted(String... urls) {
        assertAll(Arrays.stream(urls).<Executable>map(url -> () -> assertUrlAccepted(url)));
    }

    private static void assertAllRejected(String... urls) {
        assertAll(Arrays.stream(urls).<Executable>map(url -> () -> assertUrlRejected(url)));
    }

    private static String urlWithHost(String host) {
        return "jdbc:postgresql://" + host + ":" + LOCAL_PORT + "/" + LOCAL_DATABASE;
    }

    private static String urlWithPort(String port) {
        return "jdbc:postgresql://" + LOCAL_HOST + ":" + port + "/" + LOCAL_DATABASE;
    }

    private static String urlWithDatabase(String database) {
        return "jdbc:postgresql://" + LOCAL_HOST + ":" + LOCAL_PORT + "/" + database;
    }

    @Test
    void localModeWithMatchingConfigurationSucceeds() {
        assertSuccess(validate("local", LOCAL_URL, LOCAL_USER, validPolicy()));
    }

    @Test
    void ciModeWithMatchingConfigurationSucceeds() {
        assertSuccess(validate("ci", CI_URL, CI_USER, validPolicy()));
    }

    @Test
    void modeSelectsOnlyItsOwnTarget() {
        assertError(ErrorCategory.TARGET_MISMATCH, validate("ci", LOCAL_URL, LOCAL_USER, validPolicy()));
        assertError(ErrorCategory.TARGET_MISMATCH, validate("local", CI_URL, CI_USER, validPolicy()));

        Map<TestDatabaseMode, ExpectedTarget> policy = localPolicy(LOCAL_URL, LOCAL_USER);
        policy.put(TestDatabaseMode.CI, new ExpectedTarget(null, null));
        assertSuccess(validate("local", LOCAL_URL, LOCAL_USER, policy));
    }

    @Test
    void invalidModesAreRejected() {
        List<String> modes = Arrays.asList(null, "", "LOCAL", "Local", "CI", "Ci", " local", "local ", "ci\n",
                "local\u0000", "prod", "production", "test");
        assertAll(modes.stream().<Executable>map(mode -> () ->
                assertError(ErrorCategory.MODE_REJECTED, validate(mode, LOCAL_URL, LOCAL_USER, validPolicy()))));
    }

    @Test
    void nullSnapshotIsTreatedAsNullModeAndRejected() {
        assertError(ErrorCategory.MODE_REJECTED, TestDatabaseConfigValidator.validate(null, validPolicy()));
        assertError(ErrorCategory.MODE_REJECTED, TestDatabaseConfigValidator.validate(null, null));
    }

    @Test
    void missingPolicyIsReported() {
        assertError(ErrorCategory.POLICY_MISSING, validate("local", LOCAL_URL, LOCAL_USER, null));
        assertError(ErrorCategory.POLICY_MISSING, validate("local", LOCAL_URL, LOCAL_USER, Map.of()));

        Map<TestDatabaseMode, ExpectedTarget> ciOnly = new EnumMap<>(TestDatabaseMode.class);
        ciOnly.put(TestDatabaseMode.CI, new ExpectedTarget(CI_URL, CI_USER));
        assertError(ErrorCategory.POLICY_MISSING, validate("local", LOCAL_URL, LOCAL_USER, ciOnly));

        Map<TestDatabaseMode, ExpectedTarget> nullTarget = validPolicy();
        nullTarget.put(TestDatabaseMode.LOCAL, null);
        assertError(ErrorCategory.POLICY_MISSING, validate("local", LOCAL_URL, LOCAL_USER, nullTarget));
    }

    @Test
    void invalidPolicyIsReported() {
        assertError(ErrorCategory.POLICY_INVALID, validate("local", LOCAL_URL, LOCAL_USER, localPolicy(null, LOCAL_USER)));
        assertError(ErrorCategory.POLICY_INVALID, validate("local", LOCAL_URL, LOCAL_USER, localPolicy("", LOCAL_USER)));
        assertError(ErrorCategory.POLICY_INVALID, validate("local", LOCAL_URL, LOCAL_USER, localPolicy(LOCAL_URL, null)));
        assertError(ErrorCategory.POLICY_INVALID, validate("local", LOCAL_URL, LOCAL_USER, localPolicy(LOCAL_URL, "")));
        assertError(ErrorCategory.POLICY_INVALID,
                validate("local", LOCAL_URL, LOCAL_USER, localPolicy("jdbc:mysql://db-local.example.test:3306/tm", LOCAL_USER)));
    }

    @Test
    void missingConfigIsReported() {
        assertError(ErrorCategory.CONFIG_MISSING, validate("local", null, LOCAL_USER, validPolicy()));
        assertError(ErrorCategory.CONFIG_MISSING, validate("local", "", LOCAL_USER, validPolicy()));
        assertError(ErrorCategory.CONFIG_MISSING, validate("local", LOCAL_URL, null, validPolicy()));
        assertError(ErrorCategory.CONFIG_MISSING, validate("local", LOCAL_URL, "", validPolicy()));
        assertError(ErrorCategory.CONFIG_MISSING, validate("ci", null, null, validPolicy()));
    }

    @Test
    void modeRejectionHasHighestPriority() {
        assertError(ErrorCategory.MODE_REJECTED, validate("prod", "not a url", "", null));
        assertError(ErrorCategory.MODE_REJECTED, validate(null, null, null, localPolicy(null, null)));
    }

    @Test
    void missingPolicyBeatsInvalidPolicyAndMissingConfig() {
        assertError(ErrorCategory.POLICY_MISSING, validate("local", null, "", null));

        Map<TestDatabaseMode, ExpectedTarget> ciOnlyInvalid = new EnumMap<>(TestDatabaseMode.class);
        ciOnlyInvalid.put(TestDatabaseMode.CI, new ExpectedTarget("bad", ""));
        assertError(ErrorCategory.POLICY_MISSING, validate("local", "bad", null, ciOnlyInvalid));
    }

    @Test
    void invalidPolicyBeatsMissingConfig() {
        assertError(ErrorCategory.POLICY_INVALID, validate("local", null, null, localPolicy("bad", LOCAL_USER)));
        assertError(ErrorCategory.POLICY_INVALID, validate("local", "", "", localPolicy(LOCAL_URL, "")));
    }

    @Test
    void missingConfigBeatsUnsupportedUrlAndMismatch() {
        assertError(ErrorCategory.CONFIG_MISSING, validate("local", "not a url", "", validPolicy()));
        assertError(ErrorCategory.CONFIG_MISSING, validate("local", "", "someone_else", validPolicy()));
        assertError(ErrorCategory.CONFIG_MISSING, validate("local", CI_URL, null, validPolicy()));
    }

    @Test
    void unsupportedUrlBeatsMismatch() {
        assertError(ErrorCategory.URL_UNSUPPORTED,
                validate("local", "jdbc:postgresql://other.example.test:1/db?x=1", "someone_else", validPolicy()));
        assertError(ErrorCategory.URL_UNSUPPORTED, validate("local", LOCAL_URL + "/", "someone_else", validPolicy()));
    }

    @Test
    void anyDifferenceIsExactMismatch() {
        assertError(ErrorCategory.TARGET_MISMATCH, validate("local", urlWithPort("15433"), LOCAL_USER, validPolicy()));
        assertError(ErrorCategory.TARGET_MISMATCH, validate("local", urlWithDatabase("tm_local_other"), LOCAL_USER, validPolicy()));
        assertError(ErrorCategory.TARGET_MISMATCH, validate("local", urlWithHost("db-other.example.test"), LOCAL_USER, validPolicy()));
        assertError(ErrorCategory.TARGET_MISMATCH, validate("local", LOCAL_URL, "fixture_other_user", validPolicy()));
    }

    @Test
    void comparisonIsCaseSensitiveAndUntrimmed() {
        assertError(ErrorCategory.TARGET_MISMATCH, validate("local", urlWithHost("DB-LOCAL.example.test"), LOCAL_USER, validPolicy()));
        assertError(ErrorCategory.TARGET_MISMATCH, validate("local", urlWithDatabase("TM_LOCAL_FIXTURE"), LOCAL_USER, validPolicy()));
        assertError(ErrorCategory.TARGET_MISMATCH, validate("local", LOCAL_URL, "Fixture_Local_User", validPolicy()));
        assertError(ErrorCategory.TARGET_MISMATCH, validate("local", LOCAL_URL, LOCAL_USER + " ", validPolicy()));
        assertError(ErrorCategory.TARGET_MISMATCH, validate("local", LOCAL_URL, " " + LOCAL_USER, validPolicy()));
        assertError(ErrorCategory.TARGET_MISMATCH, validate("local", LOCAL_URL, LOCAL_USER + "\n", validPolicy()));
    }

    @Test
    void usernameOnlyNeedsToBeNonEmptyAndExactlyEqual() {
        assertSuccess(validate("local", LOCAL_URL, " ", localPolicy(LOCAL_URL, " ")));
        assertSuccess(validate("local", LOCAL_URL, "user@x", localPolicy(LOCAL_URL, "user@x")));
    }

    @Test
    void whitespaceAndControlCharactersAreRejected() {
        assertAllRejected(
                LOCAL_URL + "\n",
                LOCAL_URL + "\r",
                LOCAL_URL + "\r\n",
                LOCAL_URL + "\t",
                LOCAL_URL + "\f",
                LOCAL_URL + " ",
                LOCAL_URL + "\u000B",
                LOCAL_URL + "\u0000",
                LOCAL_URL + "\u001F",
                LOCAL_URL + "\u007F",
                " " + LOCAL_URL,
                urlWithHost("db-local\t.example.test"),
                urlWithPort("15432 "),
                urlWithDatabase("tm local"));
    }

    @Test
    void portBoundaries() {
        assertAllAccepted(urlWithPort("1"), urlWithPort("65535"), urlWithPort("5432"));
        assertAllRejected(
                urlWithPort("0"),
                urlWithPort("65536"),
                urlWithPort("99999"),
                urlWithPort("100000"),
                urlWithPort("05432"),
                urlWithPort("01"),
                urlWithPort("00"),
                urlWithPort("+5432"),
                urlWithPort("-1"),
                urlWithPort("54a2"),
                urlWithPort(""));
    }

    @Test
    void ipv4Octets() {
        assertAllAccepted(urlWithHost("255.255.255.255"), urlWithHost("0.0.0.0"), urlWithHost("10.0.0.1"),
                urlWithHost("192.0.2.10"));
        assertAllRejected(
                urlWithHost("256.0.0.1"),
                urlWithHost("10.0.0.256"),
                urlWithHost("010.0.0.1"),
                urlWithHost("10.0.0.01"),
                urlWithHost("10.00.0.1"));
    }

    @Test
    void malformedNumericHostsNeverFallBackToDns() {
        assertAllRejected(
                urlWithHost("1.2.3"),
                urlWithHost("1.2.3.4.5"),
                urlWithHost("1..2.3"),
                urlWithHost("1.2.3."),
                urlWithHost(".1.2.3"),
                urlWithHost("1.2.3.4."),
                urlWithHost("1.2.3.1000"),
                urlWithHost("12345"),
                urlWithHost("4294967295"),
                urlWithHost("..."),
                urlWithHost("."));
    }

    @Test
    void dnsLabelLength() {
        String label63 = "a".repeat(63);
        String label64 = "a".repeat(64);
        assertAllAccepted(urlWithHost(label63 + ".example.test"), urlWithHost(label63));
        assertAllRejected(urlWithHost(label64 + ".example.test"), urlWithHost(label64));
    }

    @Test
    void dnsTotalLength() {
        String host253 = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        String host254 = "a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(62);
        assertEquals(253, host253.length());
        assertEquals(254, host254.length());
        assertUrlAccepted(urlWithHost(host253));
        assertUrlRejected(urlWithHost(host254));
    }

    @Test
    void dnsLabelShape() {
        assertAllAccepted(urlWithHost("localhost"), urlWithHost("a"), urlWithHost("db-1.example-2.test"),
                urlWithHost("1-db.example.test"), urlWithHost("DB-Local.Example.TEST"));
        assertAllRejected(
                urlWithHost("-db.example.test"),
                urlWithHost("db-.example.test"),
                urlWithHost("db.-example.test"),
                urlWithHost("db.example-.test"),
                urlWithHost("-"),
                urlWithHost("db.example.test."),
                urlWithHost(".db.example.test"),
                urlWithHost("db..example.test"),
                urlWithHost("db_local.example.test"),
                urlWithHost("db*local.example.test"));
    }

    @Test
    void hostCaseIsPreservedNotNormalized() {
        String mixedCase = urlWithHost("DB-Local.Example.TEST");
        assertUrlAccepted(mixedCase);
        assertError(ErrorCategory.TARGET_MISMATCH,
                validate("local", mixedCase, LOCAL_USER, localPolicy(urlWithHost("db-local.example.test"), LOCAL_USER)));
    }

    @Test
    void databaseName() {
        assertAllAccepted(urlWithDatabase("_"), urlWithDatabase("9db"), urlWithDatabase("_tm_1"),
                urlWithDatabase("Tm_Local_1"));
        assertAllRejected(
                urlWithDatabase(""),
                urlWithDatabase("tm-local"),
                urlWithDatabase("tm.local"),
                urlWithDatabase("tm$local"));
    }

    @Test
    void unsupportedStructuresAreRejected() {
        assertAllRejected(
                "jdbc:postgresql://[::1]:5432/tm",
                "jdbc:postgresql://[2001:db8::1]:5432/tm",
                "jdbc:postgresql://::1:5432/tm",
                "jdbc:postgresql://h1.example.test:5432,h2.example.test:5432/tm",
                "jdbc:postgresql://h1.example.test,h2.example.test:5432/tm",
                LOCAL_URL + "?sslmode=disable",
                LOCAL_URL + "?",
                LOCAL_URL + "#fragment",
                LOCAL_URL + "#",
                "jdbc:postgresql://fixture@db-local.example.test:15432/tm",
                "jdbc:postgresql://fixture:secret@db-local.example.test:15432/tm",
                urlWithDatabase("tm%5Flocal"),
                urlWithHost("db%2Dlocal.example.test"),
                urlWithPort("%31"),
                LOCAL_URL + "/",
                "jdbc:postgresql://" + LOCAL_HOST + ":" + LOCAL_PORT + "//" + LOCAL_DATABASE,
                "jdbc:postgresql:///" + LOCAL_HOST + ":" + LOCAL_PORT + "/" + LOCAL_DATABASE,
                "jdbc:postgresql:/" + LOCAL_HOST + ":" + LOCAL_PORT + "/" + LOCAL_DATABASE,
                "jdbc:postgresql://:5432/tm",
                "jdbc:postgresql://" + LOCAL_HOST + "/" + LOCAL_DATABASE,
                "jdbc:postgresql://" + LOCAL_HOST + ":" + LOCAL_PORT,
                "jdbc:postgresql://" + LOCAL_HOST + ":" + LOCAL_PORT + "/",
                "jdbc:postgresql://" + LOCAL_HOST + ":1:" + LOCAL_PORT + "/" + LOCAL_DATABASE,
                "jdbc:postgresql:" + LOCAL_DATABASE,
                "jdbc:postgresql://",
                "JDBC:postgresql://" + LOCAL_HOST + ":" + LOCAL_PORT + "/" + LOCAL_DATABASE,
                "jdbc:PostgreSQL://" + LOCAL_HOST + ":" + LOCAL_PORT + "/" + LOCAL_DATABASE,
                "jdbc:mysql://" + LOCAL_HOST + ":" + LOCAL_PORT + "/" + LOCAL_DATABASE,
                "postgresql://" + LOCAL_HOST + ":" + LOCAL_PORT + "/" + LOCAL_DATABASE);
    }

    @Test
    void nonAsciiInputIsRejected() {
        assertAllRejected(
                urlWithHost("d\u00E9.example.test"),
                urlWithDatabase("tm_\u00E9"),
                urlWithPort("\uFF15\uFF14\uFF13\uFF12"),
                "jdbc:\u0440ostgresql://" + LOCAL_HOST + ":" + LOCAL_PORT + "/" + LOCAL_DATABASE,
                LOCAL_URL + "\u00A0",
                LOCAL_URL + "\u2028");
    }

    @Test
    void stringRepresentationsDoNotExposeRawValues() {
        Snapshot snapshot = new Snapshot("local", LOCAL_URL, LOCAL_USER);
        Snapshot invalidModeSnapshot = new Snapshot("fixture-secret-mode", CI_URL, CI_USER);
        ExpectedTarget localTarget = new ExpectedTarget(LOCAL_URL, LOCAL_USER);
        ExpectedTarget ciTarget = new ExpectedTarget(CI_URL, CI_USER);
        Result success = validate("local", LOCAL_URL, LOCAL_USER, validPolicy());
        Result mismatch = validate("local", CI_URL, CI_USER, validPolicy());
        Result unsupported = validate("local", LOCAL_URL + "?password=fixture_secret", LOCAL_USER, validPolicy());
        Result modeRejected = TestDatabaseConfigValidator.validate(invalidModeSnapshot, validPolicy());

        List<String> renderings = List.of(snapshot.toString(), invalidModeSnapshot.toString(), localTarget.toString(),
                ciTarget.toString(), success.toString(), mismatch.toString(), unsupported.toString(),
                modeRejected.toString());
        List<String> rawValues = List.of(LOCAL_URL, LOCAL_USER, LOCAL_HOST, LOCAL_PORT, LOCAL_DATABASE, CI_URL,
                CI_USER, CI_HOST, CI_PORT, CI_DATABASE, "fixture", "jdbc:", "postgresql", "example.test");

        assertAll(renderings.stream().flatMap(rendering -> rawValues.stream().<Executable>map(raw -> () ->
                assertFalse(rendering.contains(raw), () -> "rendering exposes a raw fixture value: " + rendering))));
        assertTrue(mismatch.toString().contains(ErrorCategory.TARGET_MISMATCH.name()));
        assertTrue(unsupported.toString().contains(ErrorCategory.URL_UNSUPPORTED.name()));
    }

    @Test
    void unrelatedFailuresAreNotConvertedToErrorCategories() {
        Map<TestDatabaseMode, ExpectedTarget> failingPolicy = new AbstractMap<>() {
            @Override
            public Set<Map.Entry<TestDatabaseMode, ExpectedTarget>> entrySet() {
                return Set.of();
            }

            @Override
            public ExpectedTarget get(Object key) {
                throw new IllegalArgumentException("synthetic policy failure");
            }
        };

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> validate("local", LOCAL_URL, LOCAL_USER, failingPolicy));
        assertEquals("synthetic policy failure", thrown.getMessage());
    }
}
