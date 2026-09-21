package io.github.jiangzhengxun.taskmanager.task.application.port.in;

import io.github.jiangzhengxun.taskmanager.task.domain.Task;

public interface CreateTaskUseCase {

    Task create(CreateTaskCommand command);
}
