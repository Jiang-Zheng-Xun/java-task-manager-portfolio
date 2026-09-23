package io.github.jiangzhengxun.taskmanager.task.application.port.in;

import java.util.List;

import io.github.jiangzhengxun.taskmanager.task.domain.Task;

public interface ListTasksUseCase {

    List<Task> listTasks();
}
