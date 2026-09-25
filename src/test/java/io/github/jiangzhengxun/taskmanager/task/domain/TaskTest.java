package io.github.jiangzhengxun.taskmanager.task.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class TaskTest {

    private static final Instant NOW =
            Instant.parse("2026-09-21T01:15:25Z");

    @Test
    void createNormalizesInputAndInitializesNewTask() {
        Task task = Task.create(
                "  Prepare portfolio README  ",
                "  Add API examples  ",
                NOW
        );

        assertThat(task.id()).isNull();
        assertThat(task.title()).isEqualTo("Prepare portfolio README");
        assertThat(task.description()).isEqualTo("Add API examples");
        assertThat(task.status()).isEqualTo(TaskStatus.TODO);
        assertThat(task.createdAt()).isEqualTo(NOW);
        assertThat(task.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void createNormalizesBlankDescriptionToNull() {
        Task task = Task.create("Task title", "   ", NOW);

        assertThat(task.description()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "\t\n"})
    void createRejectsBlankTitle(String title) {
        assertThatThrownBy(() -> Task.create(title, "Description", NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("title must not be blank");
    }

    @Test
    void createRejectsTitleLongerThan200Characters() {
        String title = "x".repeat(201);

        assertThatThrownBy(() -> Task.create(title, null, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("title must not exceed 200 characters");
    }

    @Test
    void createRejectsDescriptionLongerThan2000Characters() {
        String description = "x".repeat(2001);

        assertThatThrownBy(() -> Task.create("Task title", description, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("description must not exceed 2000 characters");
    }

    @Test
    void changingStatusPreservesIdentityAndCreationTime() {
        Task original = new Task(
                7L,
                "Prepare README",
                "Check examples",
                TaskStatus.TODO,
                NOW,
                NOW
        );
        Instant later = NOW.plusSeconds(60);

        Task changed = original.withStatus(TaskStatus.IN_PROGRESS, later);

        assertThat(changed.id()).isEqualTo(7L);
        assertThat(changed.title()).isEqualTo("Prepare README");
        assertThat(changed.description()).isEqualTo("Check examples");
        assertThat(changed.status()).isEqualTo(TaskStatus.IN_PROGRESS);
        assertThat(changed.createdAt()).isEqualTo(NOW);
        assertThat(changed.updatedAt()).isEqualTo(later);
        assertThat(original.status()).isEqualTo(TaskStatus.TODO);
    }

    @Test
    void repeatingSameStatusPreservesUpdatedAt() {
        Task original = new Task(
                7L,
                "Prepare README",
                null,
                TaskStatus.IN_PROGRESS,
                NOW,
                NOW
        );

        Task repeated = original.withStatus(
                TaskStatus.IN_PROGRESS,
                NOW.plusSeconds(60)
        );

        assertThat(repeated).isSameAs(original);
        assertThat(repeated.updatedAt()).isEqualTo(NOW);
    }
}
