package io.github.jiangzhengxun.taskmanager.task.application.exception;

public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(long id) {
        super("Task not found: " + id);
    }
}
