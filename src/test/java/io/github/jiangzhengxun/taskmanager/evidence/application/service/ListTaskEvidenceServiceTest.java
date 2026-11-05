package io.github.jiangzhengxun.taskmanager.evidence.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.jiangzhengxun.taskmanager.evidence.application.port.out.TaskEvidencePage;
import io.github.jiangzhengxun.taskmanager.evidence.application.port.out.TaskEvidenceRepository;
import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;
import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ListTaskEvidenceServiceTest {

    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final TaskEvidenceRepository evidenceRepository = mock(TaskEvidenceRepository.class);
    private static final Task TASK = new Task(1L, "t", null, TaskStatus.TODO,
            java.time.Instant.parse("2026-11-01T00:00:00Z"), java.time.Instant.parse("2026-11-01T00:00:00Z"));
    private final ListTaskEvidenceService service = new ListTaskEvidenceService(taskRepository, evidenceRepository);

    @ParameterizedTest
    @CsvSource({"0,0,20", "-1,0,20"})
    void nonPositiveTaskIdNeverTouchesRepositories(long taskId, int page, int size) {
        assertThatThrownBy(() -> service.listEvidence(taskId, page, size)).hasMessage("id must be positive");
        verifyNoInteractions(taskRepository, evidenceRepository);
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,101", "-2147483648,1"})
    void invalidPaginationNeverTouchesRepositories(int page, int size) {
        assertThatThrownBy(() -> service.listEvidence(1L, page, size))
                .hasMessage("page must be non-negative and size must be between 1 and 100");
        verifyNoInteractions(taskRepository, evidenceRepository);
    }

    @Test
    void missingTaskIs404EvenWithHugeOffset() {
        when(taskRepository.findById(1L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.listEvidence(1L, Integer.MAX_VALUE, 100))
                .isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(evidenceRepository);
    }

    @Test
    void hugeOffsetReturnsEmptyPageWithoutQueryOrOverflow() {
        when(taskRepository.findById(1L)).thenReturn(Optional.of(TASK));
        TaskEvidencePage page = service.listEvidence(1L, Integer.MAX_VALUE, 100);
        assertThat(page.evidence()).isEmpty();
        assertThat(page.hasNext()).isFalse();
        verify(evidenceRepository, never()).findPage(anyLong(), anyLong(), anyInt());
    }

    @Test
    void validRequestDelegatesWithLongOffset() {
        when(taskRepository.findById(1L)).thenReturn(Optional.of(TASK));
        when(evidenceRepository.findPage(1L, 40L, 20)).thenReturn(new TaskEvidencePage(List.of(), true));
        assertThat(service.listEvidence(1L, 2, 20).hasNext()).isTrue();
    }
}
