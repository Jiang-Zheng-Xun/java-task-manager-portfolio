package io.github.jiangzhengxun.taskmanager.task.api;

import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.GetTaskByIdUseCase;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;

@RestController
@RequestMapping("/api/tasks")
public class GetTaskByIdController {

    private final GetTaskByIdUseCase getTaskByIdUseCase;

    public GetTaskByIdController(
            GetTaskByIdUseCase getTaskByIdUseCase) {
        this.getTaskByIdUseCase = Objects.requireNonNull(
                getTaskByIdUseCase,
                "getTaskByIdUseCase must not be null");
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskResponse> getById(
            @PathVariable long id) {
        Task task = getTaskByIdUseCase.getById(id);

        return ResponseEntity.ok(TaskResponse.from(task));
    }
}
