package io.github.jiangzhengxun.taskmanager.task.application.service;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.ListTasksUseCase;
import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;
import io.github.jiangzhengxun.taskmanager.task.application.model.TaskPage;

@Service
public class ListTasksService implements ListTasksUseCase {

    private final TaskRepository taskRepository;

    public ListTasksService(TaskRepository taskRepository) {
        this.taskRepository = Objects.requireNonNull(
                taskRepository,
                "taskRepository must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public TaskPage listTasks(int page, int size) {
        return taskRepository.findPageByIdAscending(page, size);
    }
}
