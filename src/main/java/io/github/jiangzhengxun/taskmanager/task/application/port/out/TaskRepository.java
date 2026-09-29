package io.github.jiangzhengxun.taskmanager.task.application.port.out;

import java.util.Optional;

import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.application.model.TaskPage;

public interface TaskRepository {

    Task save(Task task);

    Optional<Task> findById(long id);

    boolean deleteById(long id);

    TaskPage findPageByIdAscending(int page, int size);
}
