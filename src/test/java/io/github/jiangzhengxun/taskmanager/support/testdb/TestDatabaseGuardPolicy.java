package io.github.jiangzhengxun.taskmanager.support.testdb;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Expected test database targets supplied by the caller, independent of the resolved settings snapshot.
 *
 * <p>The policy holds no password. Its string representation is redacted.
 */
public final class TestDatabaseGuardPolicy {

    private final Map<TestDatabaseMode, TestDatabaseConfigValidator.ExpectedTarget> targets;

    private TestDatabaseGuardPolicy(Map<TestDatabaseMode, TestDatabaseConfigValidator.ExpectedTarget> targets) {
        this.targets = targets;
    }

    public static TestDatabaseGuardPolicy of(Map<TestDatabaseMode, TestDatabaseConfigValidator.ExpectedTarget> targets) {
        Objects.requireNonNull(targets, "targets");
        EnumMap<TestDatabaseMode, TestDatabaseConfigValidator.ExpectedTarget> copy = new EnumMap<>(TestDatabaseMode.class);
        copy.putAll(targets);
        return new TestDatabaseGuardPolicy(Collections.unmodifiableMap(copy));
    }

    Map<TestDatabaseMode, TestDatabaseConfigValidator.ExpectedTarget> targets() {
        return targets;
    }

    @Override
    public String toString() {
        return "TestDatabaseGuardPolicy[redacted]";
    }
}
