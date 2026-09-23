package io.github.jiangzhengxun.taskmanager.task.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;

@DataJpaTest
@AutoConfigureTestDatabase(
        replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TaskRepositoryAdapter.class, TaskMapper.class})
class TaskRepositoryAdapterIntegrationTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TaskJpaRepository taskJpaRepository;

    @Test
    void savesTaskAndReturnsDatabaseGeneratedId() {
        Instant now = Instant.parse("2026-09-21T06:00:00Z");
        Task task = Task.create(
                "Prepare portfolio README",
                "Add API examples",
                now);

        Task savedTask = taskRepository.save(task);

        assertThat(savedTask.id()).isNotNull();
        assertThat(savedTask.title())
                .isEqualTo("Prepare portfolio README");
        assertThat(savedTask.description())
                .isEqualTo("Add API examples");
        assertThat(savedTask.status()).isEqualTo(TaskStatus.TODO);
        assertThat(savedTask.createdAt()).isEqualTo(now);
        assertThat(savedTask.updatedAt()).isEqualTo(now);

        TaskEntity persistedEntity = taskJpaRepository
                .findById(savedTask.id())
                .orElseThrow();

        assertThat(persistedEntity.getTitle())
                .isEqualTo("Prepare portfolio README");
        assertThat(persistedEntity.getDescription())
                .isEqualTo("Add API examples");
        assertThat(persistedEntity.getStatus())
                .isEqualTo(TaskStatus.TODO);
        assertThat(persistedEntity.getCreatedAt()).isEqualTo(now);
        assertThat(persistedEntity.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void findsPersistedTaskById() {
        Instant now = Instant.parse("2026-09-22T01:45:00Z");
        Task savedTask = taskRepository.save(Task.create(
                "Read Task by ID",
                "Verify repository lookup",
                now));

        assertThat(taskRepository.findById(savedTask.id()))
                .contains(savedTask);
    }

    @Test
    void returnsEmptyWhenTaskDoesNotExist() {
        assertThat(taskRepository.findById(Long.MAX_VALUE))
                .isEmpty();
    }

    @Test
    void returnsEmptyListWhenNoTasksExist() {
        taskJpaRepository.deleteAll();

        assertThat(taskRepository.findAllByIdAscending())
                .isEmpty();
    }

    @Test
    void findsAllTasksInAscendingIdOrder() {
        taskJpaRepository.deleteAll();

        Task firstSavedTask = taskRepository.save(Task.create(
                "First collection task",
                "Verify first representation",
                Instant.parse("2026-09-23T02:00:00Z")));
        Task secondSavedTask = taskRepository.save(Task.create(
                "Second collection task",
                "Verify second representation",
                Instant.parse("2026-09-23T02:01:00Z")));

        assertThat(taskRepository.findAllByIdAscending())
                .containsExactly(firstSavedTask, secondSavedTask);
    }
}
