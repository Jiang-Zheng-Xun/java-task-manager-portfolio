package io.github.jiangzhengxun.taskmanager.task.application.service;

import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.ListTasksUseCase;
import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;

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
    public List<Task> listTasks() {
        return taskRepository.findAllByIdAscending();
    }
}
