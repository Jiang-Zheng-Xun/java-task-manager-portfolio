package io.github.jiangzhengxun.taskmanager.task.infrastructure.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.List;

import org.springframework.stereotype.Repository;

import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;

@Repository
public class TaskRepositoryAdapter implements TaskRepository {

    private final TaskJpaRepository taskJpaRepository;
    private final TaskMapper taskMapper;

    public TaskRepositoryAdapter(
            TaskJpaRepository taskJpaRepository,
            TaskMapper taskMapper) {
        this.taskJpaRepository = Objects.requireNonNull(
                taskJpaRepository,
                "taskJpaRepository must not be null");
        this.taskMapper = Objects.requireNonNull(
                taskMapper,
                "taskMapper must not be null");
    }

    @Override
    public Task save(Task task) {
        TaskEntity entity = taskMapper.toEntity(task);
        TaskEntity savedEntity = taskJpaRepository.save(entity);
        return taskMapper.toDomain(savedEntity);
    }

    @Override
    public Optional<Task> findById(long id) {
        return taskJpaRepository.findById(id)
                .map(taskMapper::toDomain);
    }

    @Override
    public List<Task> findAllByIdAscending() {
        return taskJpaRepository.findAllByOrderByIdAsc().stream()
                .map(taskMapper::toDomain)
                .toList();
    }
}
