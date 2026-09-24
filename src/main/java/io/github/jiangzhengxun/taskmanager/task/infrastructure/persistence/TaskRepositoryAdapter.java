package io.github.jiangzhengxun.taskmanager.task.infrastructure.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.List;

import org.springframework.stereotype.Repository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;

import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.application.model.TaskPage;

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
    public TaskPage findPageByIdAscending(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("Invalid pagination parameters");
        }

        // JPA's row offset must fit an int. A larger offset cannot contain
        // any page supported by this persistence query.
        if ((long) page * size > Integer.MAX_VALUE) {
            return new TaskPage(List.of(), false);
        }

        Slice<TaskEntity> entities = taskJpaRepository
                .findAllByOrderByIdAsc(PageRequest.of(page, size));

        List<Task> tasks = entities.getContent().stream()
                .map(taskMapper::toDomain)
                .toList();

        return new TaskPage(tasks, entities.hasNext());
    }
}
