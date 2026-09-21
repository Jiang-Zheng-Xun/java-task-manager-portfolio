package io.github.jiangzhengxun.taskmanager.task.application.port.in;

public record CreateTaskCommand(
        String title,
        String description) {
}
