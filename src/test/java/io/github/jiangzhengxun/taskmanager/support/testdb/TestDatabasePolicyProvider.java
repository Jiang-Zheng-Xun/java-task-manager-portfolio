package io.github.jiangzhengxun.taskmanager.support.testdb;

import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Reason;
import io.github.jiangzhengxun.taskmanager.support.testdb.TestDatabaseGuardException.Stage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * Supplies the independent guard and identity policies for the selected {@code TEST_DB_MODE}.
 *
 * <p>{@code ci} uses the fixed policy below and must not name a policy file. {@code local} loads a private
 * {@link Properties} file named by {@value #POLICY_FILE_PROPERTY}, which must be an absolute path to an existing regular
 * file (symbolic links are not followed). The file must contain exactly the keys in {@link #FIELDS}, each non-blank and
 * each only once (after {@link Properties} unescaping); a missing file, missing field, blank value, unknown field or
 * repeated key is rejected. {@code effective-role} and {@code login-role} must both equal {@code username}; the
 * {@code owner-role} stays independent. Before anything connects, the {@code jdbc-url} must satisfy the narrow lexical
 * contract of {@link TestDatabaseConfigValidator} and its database must equal {@code database} exactly. Nothing is
 * derived from the datasource settings under test, and the file holds no password. Diagnostics are fixed codes; file
 * names, keys, values and I/O causes are never reported.
 */
public final class TestDatabasePolicyProvider {

    public static final String POLICY_FILE_PROPERTY = "TEST_DB_POLICY_FILE";

    static final String JDBC_URL = "jdbc-url";
    static final String USERNAME = "username";
    static final String DATABASE = "database";
    static final String LOGIN_ROLE = "login-role";
    static final String EFFECTIVE_ROLE = "effective-role";
    static final String OWNER_ROLE = "owner-role";
    static final Set<String> FIELDS = Set.of(JDBC_URL, USERNAME, DATABASE, LOGIN_ROLE, EFFECTIVE_ROLE, OWNER_ROLE);

    static final String CI_JDBC_URL = "jdbc:postgresql://localhost:5432/task_manager_test";
    static final String CI_DATABASE = "task_manager_test";
    static final String CI_ROLE = "jtm_test";

    // Prefix of the only URL form the validator accepts; used solely after the validator accepted the URL.
    private static final String JDBC_URL_PREFIX = "jdbc:postgresql://";

    /** Both policies for one mode; the guard policy holds only that mode's target. */
    record Resolved(TestDatabaseMode mode, TestDatabaseGuardPolicy guardPolicy, TestDatabaseIdentityPolicy identity) {

        @Override
        public String toString() {
            return "TestDatabasePolicyProvider.Resolved[redacted]";
        }
    }

    private TestDatabasePolicyProvider() {
    }

    static Resolved resolve(Environment environment) {
        String rawMode;
        String policyFile;
        try {
            rawMode = environment.getProperty(TestDatabaseGuardInitializer.MODE_PROPERTY);
            policyFile = environment.getProperty(POLICY_FILE_PROPERTY);
        } catch (RuntimeException unresolvable) {
            throw reject(Reason.SETTINGS_UNRESOLVABLE);
        }
        return resolve(rawMode, policyFile);
    }

    static Resolved resolve(String rawMode, String policyFile) {
        TestDatabaseMode mode;
        try {
            mode = TestDatabaseMode.parse(rawMode);
        } catch (IllegalArgumentException rejected) {
            throw reject(Reason.MODE_REJECTED);
        }
        if (mode == TestDatabaseMode.CI) {
            if (policyFile != null) {
                throw reject(Reason.POLICY_INVALID);
            }
            return fromFields(mode, Map.of(JDBC_URL, CI_JDBC_URL, USERNAME, CI_ROLE, DATABASE, CI_DATABASE,
                    LOGIN_ROLE, CI_ROLE, EFFECTIVE_ROLE, CI_ROLE, OWNER_ROLE, CI_ROLE));
        }
        return fromFields(mode, load(policyFile));
    }

    static Map<String, String> load(String policyFile) {
        if (policyFile == null || policyFile.isBlank()) {
            throw reject(Reason.POLICY_FILE_MISSING);
        }
        Path path;
        try {
            path = Path.of(policyFile);
        } catch (InvalidPathException invalid) {
            throw reject(Reason.POLICY_FILE_MISSING);
        }
        if (!path.isAbsolute() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw reject(Reason.POLICY_FILE_MISSING);
        }
        Properties properties = new DuplicateRejectingProperties();
        try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            properties.load(in);
        } catch (TestDatabaseGuardException duplicate) {
            // A fresh exception keeps the fixed reason and drops any suppressed close failure.
            throw reject(duplicate.reason());
        } catch (IOException | RuntimeException unreadable) {
            throw reject(Reason.POLICY_FILE_UNREADABLE);
        }
        return parse(properties);
    }

    static Map<String, String> parse(Properties properties) {
        for (String name : properties.stringPropertyNames()) {
            if (!FIELDS.contains(name)) {
                throw reject(Reason.POLICY_FIELD_UNKNOWN);
            }
        }
        if (properties.size() != properties.stringPropertyNames().size()) {
            throw reject(Reason.POLICY_FIELD_UNKNOWN);
        }
        Map<String, String> fields = new HashMap<>();
        for (String field : FIELDS) {
            String value = properties.getProperty(field);
            if (value == null || value.isBlank()) {
                throw reject(Reason.POLICY_FIELD_MISSING);
            }
            fields.put(field, value);
        }
        return Map.copyOf(fields);
    }

    private static Resolved fromFields(TestDatabaseMode mode, Map<String, String> fields) {
        String username = fields.get(USERNAME);
        if (!fields.get(LOGIN_ROLE).equals(username) || !fields.get(EFFECTIVE_ROLE).equals(username)) {
            throw reject(Reason.POLICY_INVALID);
        }
        TestDatabaseConfigValidator.ExpectedTarget target;
        try {
            target = new TestDatabaseConfigValidator.ExpectedTarget(fields.get(JDBC_URL), username);
        } catch (RuntimeException invalid) {
            throw reject(Reason.POLICY_INVALID);
        }
        requireUrlNamesDatabase(mode, target, fields.get(DATABASE));
        return new Resolved(mode, TestDatabaseGuardPolicy.of(Map.of(mode, target)),
                new TestDatabaseIdentityPolicy(fields.get(DATABASE), fields.get(LOGIN_ROLE),
                        fields.get(EFFECTIVE_ROLE), fields.get(OWNER_ROLE)));
    }

    /** Applies the existing validator's URL contract to the policy URL, then compares its database exactly. */
    private static void requireUrlNamesDatabase(TestDatabaseMode mode, TestDatabaseConfigValidator.ExpectedTarget target,
            String database) {
        TestDatabaseConfigValidator.Result result;
        try {
            // The target checked against itself can only fail on the policy URL's own lexical contract.
            result = TestDatabaseConfigValidator.validate(new TestDatabaseConfigValidator.Snapshot(
                    mode.name().toLowerCase(Locale.ROOT), target.jdbcUrl(), target.username()), Map.of(mode, target));
        } catch (RuntimeException invalid) {
            throw reject(Reason.POLICY_INVALID);
        }
        if (!result.isSuccess()) {
            throw reject(Reason.POLICY_INVALID);
        }
        // Accepted URLs are exactly jdbc:postgresql://host:port/database, so the database follows the first slash.
        String remainder = target.jdbcUrl().substring(JDBC_URL_PREFIX.length());
        if (!remainder.substring(remainder.indexOf('/') + 1).equals(database)) {
            throw reject(Reason.POLICY_INVALID);
        }
    }

    private static TestDatabaseGuardException reject(Reason reason) {
        return new TestDatabaseGuardException(Stage.POLICY, reason);
    }

    /**
     * Rejects any key {@link Properties#load} delivers twice. Keys arrive already unescaped, so repeats with the same
     * value, a different value or an escape-equivalent spelling are all caught; keys and values are never reported.
     */
    private static final class DuplicateRejectingProperties extends Properties {

        private static final long serialVersionUID = 1L;

        @Override
        public synchronized Object put(Object key, Object value) {
            if (containsKey(key)) {
                throw reject(Reason.POLICY_FIELD_DUPLICATE);
            }
            return super.put(key, value);
        }
    }
}
