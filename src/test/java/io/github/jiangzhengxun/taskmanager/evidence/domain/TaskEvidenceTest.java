package io.github.jiangzhengxun.taskmanager.evidence.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaskEvidenceTest {

    private static final Instant NANOS = Instant.parse("2026-11-01T07:00:00.123456789Z");

    @Test
    void newEvidenceHasNullIdAndIsNotPersisted() {
        TaskEvidence evidence = TaskEvidence.newEvidence(1L, " a ", NANOS);
        assertThat(evidence.id()).isNull();
        assertThat(evidence.isPersisted()).isFalse();
        assertThat(evidence.content()).isEqualTo("a");
    }

    @Test
    void createdAtIsTruncatedToMicrosOnEveryPath() {
        Instant micros = Instant.parse("2026-11-01T07:00:00.123456Z");
        assertThat(TaskEvidence.newEvidence(1L, "a", NANOS).createdAt()).isEqualTo(micros);
        assertThat(TaskEvidence.restore(5L, 1L, "a", NANOS).createdAt()).isEqualTo(micros);
        assertThat(new TaskEvidence(5L, 1L, "a", NANOS).createdAt()).isEqualTo(micros);
    }

    @Test
    void canonicalConstructorCannotBypassContentRules() {
        assertThatThrownBy(() -> new TaskEvidence(5L, 1L, " ", NANOS)).hasMessage(EvidenceContent.BLANK);
        assertThatThrownBy(() -> new TaskEvidence(5L, 1L, null, NANOS)).hasMessage(EvidenceContent.NOT_STRING);
        assertThatThrownBy(() -> new TaskEvidence(5L, 1L, "\uD800", NANOS)).hasMessage(EvidenceContent.UNSUPPORTED);
        assertThatThrownBy(() -> new TaskEvidence(5L, 1L, "a".repeat(2001), NANOS))
                .hasMessage(EvidenceContent.TOO_LONG);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void rejectsNonPositiveTaskId(long taskId) {
        assertThatThrownBy(() -> TaskEvidence.newEvidence(taskId, "a", NANOS)).hasMessage("taskId must be positive");
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void rejectsNonPositivePersistedId(long id) {
        assertThatThrownBy(() -> TaskEvidence.restore(id, 1L, "a", NANOS)).hasMessage("id must be positive");
    }

    @Test
    void rejectsNullCreatedAt() {
        assertThatThrownBy(() -> TaskEvidence.newEvidence(1L, "a", null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void keepsSupplementaryCharactersAndInteriorFormatting() {
        String value = "😀 line1\n\tline2 😀";
        assertThat(TaskEvidence.newEvidence(1L, "　" + value + " ", NANOS).content()).isEqualTo(value);
    }
}
