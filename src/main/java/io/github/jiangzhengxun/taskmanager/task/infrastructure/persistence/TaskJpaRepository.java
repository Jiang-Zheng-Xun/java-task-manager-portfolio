package io.github.jiangzhengxun.taskmanager.task.infrastructure.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

interface TaskJpaRepository extends JpaRepository<TaskEntity, Long> {

    List<TaskEntity> findAllByOrderByIdAsc();
}
