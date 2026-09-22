package io.github.jiangzhengxun.taskmanager.task.application.service;

import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.GetTaskByIdUseCase;
import io.github.jiangzhengxun.taskmanager.task.application.port.out.TaskRepository;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;
import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;

@Service
public class GetTaskByIdService implements GetTaskByIdUseCase {

    private final TaskRepository taskRepository;

    public GetTaskByIdService(TaskRepository taskRepository) {
        this.taskRepository = Objects.requireNonNull(
                taskRepository,
                "taskRepository must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public Task getById(long id) {
        if(id <= 0){
            throw new IllegalArgumentException("id must be positive");
        }

        return taskRepository.findById(id)
                .orElseThrow(() -> new TaskNotFoundException(id));
    }
}
