package io.github.jiangzhengxun.taskmanager.task.application.port.in;

import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.domain.TaskStatus;

public interface ReplaceTaskUseCase {
    Task replaceTask(
            long id, String title, String description, TaskStatus status);
}
