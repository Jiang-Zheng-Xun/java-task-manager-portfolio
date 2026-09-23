package io.github.jiangzhengxun.taskmanager.task.application.port.out;

import java.util.Optional;
import java.util.List;

import io.github.jiangzhengxun.taskmanager.task.domain.Task;

public interface TaskRepository {

    Task save(Task task);

    Optional<Task> findById(long id);

    List<Task> findAllByIdAscending();
}
