package io.github.jiangzhengxun.taskmanager.task.application.port.in;

import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;

public interface UpdateTaskStatusUseCase {
    Task updateStatus(long id, TaskStatus status);
}
