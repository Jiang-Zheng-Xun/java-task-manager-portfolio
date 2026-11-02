package io.github.jiangzhengxun.taskmanager.evidence.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.jiangzhengxun.taskmanager.evidence.domain.EvidenceContent;
import io.github.jiangzhengxun.taskmanager.evidence.domain.TaskEvidence;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TaskEvidenceMapperTest {

    @Test
    void roundTripPreservesAllFields() {
        TaskEvidence original = TaskEvidence.restore(7L, 3L, "x 😀", Instant.parse("2026-11-01T00:00:00.000001Z"));
        TaskEvidence back = TaskEvidenceMapper.toDomain(TaskEvidenceMapper.toEntity(original));
        assertThat(back).isEqualTo(original);
    }

    @Test
    void newEvidenceMapsToEntityWithNullId() {
        TaskEvidenceEntity entity = TaskEvidenceMapper.toEntity(
                TaskEvidence.newEvidence(3L, "x", Instant.parse("2026-11-01T00:00:00Z")));
        assertThat(entity.getId()).isNull();
    }

    @Test
    void invalidStoredRowIsRejectedOnMapping() {
        // DB CHECK (BTRIM) is weaker than the Java rule; such a row must not become a domain object.
        TaskEvidenceEntity entity = new TaskEvidenceEntity(7L, 3L, "　", Instant.parse("2026-11-01T00:00:00Z"));
        assertThatThrownBy(() -> TaskEvidenceMapper.toDomain(entity)).hasMessage(EvidenceContent.BLANK);
    }
}
