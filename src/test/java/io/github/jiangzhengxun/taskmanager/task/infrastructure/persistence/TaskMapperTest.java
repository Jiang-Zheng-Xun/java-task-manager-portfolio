package io.github.jiangzhengxun.taskmanager.task.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;

class TaskMapperTest {

    private final TaskMapper mapper = new TaskMapper();

    @Test
    void convertsDomainTaskToEntity() {
        Instant createdAt = Instant.parse("2026-09-21T01:00:00Z");
        Instant updatedAt = Instant.parse("2026-09-21T02:00:00Z");

        Task task = new Task(
                42L,
                "Prepare portfolio README",
                "Add API examples",
                TaskStatus.TODO,
                createdAt,
                updatedAt);

        TaskEntity entity = mapper.toEntity(task);

        assertThat(entity.getId()).isEqualTo(42L);
        assertThat(entity.getTitle()).isEqualTo("Prepare portfolio README");
        assertThat(entity.getDescription()).isEqualTo("Add API examples");
        assertThat(entity.getStatus()).isEqualTo(TaskStatus.TODO);
        assertThat(entity.getCreatedAt()).isEqualTo(createdAt);
        assertThat(entity.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void convertsEntityToDomainTask() {
        Instant createdAt = Instant.parse("2026-09-21T01:00:00Z");
        Instant updatedAt = Instant.parse("2026-09-21T02:00:00Z");

        TaskEntity entity = new TaskEntity(
                42L,
                "Prepare portfolio README",
                "Add API examples",
                TaskStatus.TODO,
                createdAt,
                updatedAt);

        Task task = mapper.toDomain(entity);

        assertThat(task.id()).isEqualTo(42L);
        assertThat(task.title()).isEqualTo("Prepare portfolio README");
        assertThat(task.description()).isEqualTo("Add API examples");
        assertThat(task.status()).isEqualTo(TaskStatus.TODO);
        assertThat(task.createdAt()).isEqualTo(createdAt);
        assertThat(task.updatedAt()).isEqualTo(updatedAt);
    }
}
