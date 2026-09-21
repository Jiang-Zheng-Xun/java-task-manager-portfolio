package io.github.jiangzhengxun.taskmanager.task.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

interface TaskJpaRepository extends JpaRepository<TaskEntity, Long> {
}
