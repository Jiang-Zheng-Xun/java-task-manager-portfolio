package io.github.jiangzhengxun.taskmanager.evidence.infrastructure.persistence;

import io.github.jiangzhengxun.taskmanager.evidence.application.port.out.TaskEvidencePage;
import io.github.jiangzhengxun.taskmanager.evidence.application.port.out.TaskEvidenceRepository;
import io.github.jiangzhengxun.taskmanager.evidence.domain.TaskEvidence;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * EntityManager-based adapter. Deliberately not a Spring Data repository interface so that the
 * Issue #39 fixed repository bean-definition group (taskJpaRepository) is not extended.
 */
@Repository
public class TaskEvidenceRepositoryAdapter implements TaskEvidenceRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void applyLocalLockTimeout() {
        entityManager.createNativeQuery("SELECT set_config('lock_timeout', '5000ms', true)")
                .getSingleResult();
    }

    @Override
    public boolean lockParentTaskForKeyShare(long taskId) {
        List<?> rows = entityManager
                .createNativeQuery("SELECT id FROM tasks WHERE id = :id FOR KEY SHARE")
                .setParameter("id", taskId)
                .getResultList();
        return !rows.isEmpty();
    }

    @Override
    public TaskEvidence insertAndFlush(TaskEvidence evidence) {
        TaskEvidenceEntity entity = TaskEvidenceMapper.toEntity(evidence);
        entityManager.persist(entity);
        entityManager.flush();
        return TaskEvidenceMapper.toDomain(entity);
    }

    @Override
    public TaskEvidencePage findPage(long taskId, long offset, int size) {
        List<TaskEvidenceEntity> rows = entityManager.createQuery(
                        "SELECT e FROM TaskEvidenceEntity e WHERE e.taskId = :taskId"
                                + " ORDER BY e.createdAt DESC, e.id DESC",
                        TaskEvidenceEntity.class)
                .setParameter("taskId", taskId)
                .setFirstResult((int) offset)
                .setMaxResults(size + 1)
                .getResultList();
        boolean hasNext = rows.size() > size;
        List<TaskEvidence> page = rows.stream().limit(size).map(TaskEvidenceMapper::toDomain).toList();
        return new TaskEvidencePage(page, hasNext);
    }
}
