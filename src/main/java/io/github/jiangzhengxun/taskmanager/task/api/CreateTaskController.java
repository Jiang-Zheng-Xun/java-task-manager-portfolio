package io.github.jiangzhengxun.taskmanager.task.api;

import java.util.Objects;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.CreateTaskCommand;
import io.github.jiangzhengxun.taskmanager.task.application.port.in.CreateTaskUseCase;
import io.github.jiangzhengxun.taskmanager.task.domain.Task;

@RestController
@RequestMapping("/api/tasks")
public class CreateTaskController {

    private final CreateTaskUseCase createTaskUseCase;

    public CreateTaskController(CreateTaskUseCase createTaskUseCase) {
        this.createTaskUseCase = Objects.requireNonNull(
                createTaskUseCase,
                "createTaskUseCase must not be null");
    }

    @PostMapping
    public ResponseEntity<CreateTaskResponse> create(
        @Valid @RequestBody CreateTaskRequest request) {
        CreateTaskCommand command = new CreateTaskCommand(
                request.title(),
                request.description());

        Task createdTask = createTaskUseCase.create(command);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(CreateTaskResponse.from(createdTask));
    }
}
