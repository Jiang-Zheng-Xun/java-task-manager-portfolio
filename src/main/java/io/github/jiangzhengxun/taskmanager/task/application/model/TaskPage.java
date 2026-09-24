package io.github.jiangzhengxun.taskmanager.task.application.model;

import java.util.List;

import io.github.jiangzhengxun.taskmanager.task.domain.Task;

public record TaskPage(List<Task> tasks, boolean hasNext) {

    public TaskPage {
        tasks = List.copyOf(tasks);
    }
}
