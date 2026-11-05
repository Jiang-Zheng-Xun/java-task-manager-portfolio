package io.github.jiangzhengxun.taskmanager.evidence.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.jiangzhengxun.taskmanager.evidence.application.port.out.TaskEvidenceRepository;
import io.github.jiangzhengxun.taskmanager.evidence.domain.EvidenceContent;
import io.github.jiangzhengxun.taskmanager.evidence.domain.TaskEvidence;
import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;

class CreateTaskEvidenceServiceTest {

    private final TaskEvidenceRepository repository = mock(TaskEvidenceRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T07:00:00.123456789Z"), ZoneOffset.UTC);
    private final CreateTaskEvidenceService service = new CreateTaskEvidenceService(repository, clock);

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void nonPositiveTaskIdNeverTouchesRepository(long taskId) {
        assertThatThrownBy(() -> service.createEvidence(taskId, "a")).hasMessage("id must be positive");
        verifyNoInteractions(repository);
    }

    @Test
    void invalidTaskIdIsReportedBeforeInvalidContent() {
        assertThatThrownBy(() -> service.createEvidence(0L, null)).hasMessage("id must be positive");
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {" ", "\u0000", "\uDC00"})
    void invalidContentNeverTouchesRepository(String raw) {
        assertThatThrownBy(() -> service.createEvidence(1L, raw)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void nullContentIsNotAStringAndNeverTouchesRepository() {
        assertThatThrownBy(() -> service.createEvidence(1L, null)).hasMessage(EvidenceContent.NOT_STRING);
        verifyNoInteractions(repository);
    }

    @Test
    void orchestratesTimeoutThenLockThenInsert() {
        when(repository.lockParentTaskForKeyShare(1L)).thenReturn(true);
        when(repository.insertAndFlush(any())).thenAnswer(invocation -> {
            TaskEvidence in = invocation.getArgument(0);
            return TaskEvidence.restore(10L, in.taskId(), in.content(), in.createdAt());
        });

        TaskEvidence result = service.createEvidence(1L, "  proof  ");

        InOrder order = inOrder(repository);
        order.verify(repository).applyLocalLockTimeout();
        order.verify(repository).lockParentTaskForKeyShare(1L);
        ArgumentCaptor<TaskEvidence> captor = ArgumentCaptor.forClass(TaskEvidence.class);
        order.verify(repository).insertAndFlush(captor.capture());
        assertThat(captor.getValue().id()).isNull();
        assertThat(captor.getValue().content()).isEqualTo("proof");
        assertThat(captor.getValue().createdAt()).isEqualTo(Instant.parse("2026-11-01T07:00:00.123456Z"));
        assertThat(result.id()).isEqualTo(10L);
    }

    @Test
    void missingParentThrowsNotFoundWithoutInsert() {
        when(repository.lockParentTaskForKeyShare(9L)).thenReturn(false);
        assertThatThrownBy(() -> service.createEvidence(9L, "a")).isInstanceOf(TaskNotFoundException.class);
        verify(repository, never()).insertAndFlush(any());
    }

    @Test
    void integrityFailurePropagatesUnchanged() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("x");
        when(repository.lockParentTaskForKeyShare(1L)).thenReturn(true);
        when(repository.insertAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> service.createEvidence(1L, "a")).isSameAs(failure);
    }

    @Test
    void lockFailurePropagatesUnchangedAndIsNotRetried() {
        PessimisticLockingFailureException failure = new PessimisticLockingFailureException("x");
        when(repository.lockParentTaskForKeyShare(1L)).thenThrow(failure);
        assertThatThrownBy(() -> service.createEvidence(1L, "a")).isSameAs(failure);
        verify(repository).lockParentTaskForKeyShare(1L);
        verify(repository, never()).insertAndFlush(any());
    }
    // Note: mocks do not prove rollback; see TEST_PLAN section E.
}
