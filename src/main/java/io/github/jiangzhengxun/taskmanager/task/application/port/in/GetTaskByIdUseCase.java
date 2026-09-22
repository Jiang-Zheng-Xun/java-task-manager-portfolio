package io.github.jiangzhengxun.taskmanager.task.application.port.in;

import io.github.jiangzhengxun.taskmanager.task.domain.Task;

public interface GetTaskByIdUseCase {

    Task getById(long id);
}
