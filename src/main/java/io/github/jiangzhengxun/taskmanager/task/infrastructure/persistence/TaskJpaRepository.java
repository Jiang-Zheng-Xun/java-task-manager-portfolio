package io.github.jiangzhengxun.taskmanager.task.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

interface TaskJpaRepository extends JpaRepository<TaskEntity, Long> {

    Slice<TaskEntity> findAllByOrderByIdAsc(Pageable pageable);
}
