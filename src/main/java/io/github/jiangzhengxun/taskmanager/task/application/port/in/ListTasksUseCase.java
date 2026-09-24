package io.github.jiangzhengxun.taskmanager.task.application.port.in;

import io.github.jiangzhengxun.taskmanager.task.application.model.TaskPage;

public interface ListTasksUseCase {

    TaskPage listTasks(int page, int size);
}
