package io.github.jiangzhengxun.taskmanager.evidence.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "task_evidence")
public class TaskEvidenceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, updatable = false)
    private long taskId;

    @Column(nullable = false, length = 2000, updatable = false)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TaskEvidenceEntity() {
    }

    TaskEvidenceEntity(Long id, long taskId, String content, Instant createdAt) {
        this.id = id;
        this.taskId = taskId;
        this.content = content;
        this.createdAt = createdAt;
    }

    Long getId() { return id; }

    long getTaskId() { return taskId; }

    String getContent() { return content; }

    Instant getCreatedAt() { return createdAt; }
}
