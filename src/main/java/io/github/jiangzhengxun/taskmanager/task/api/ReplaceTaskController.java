package io.github.jiangzhengxun.taskmanager.task.api;

import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.ReplaceTaskUseCase;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/tasks")
public class ReplaceTaskController {

    private final ReplaceTaskUseCase useCase;

    public ReplaceTaskController(ReplaceTaskUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TaskResponse> replaceTask(
            @PathVariable long id,
            @Valid @RequestBody ReplaceTaskRequest request) {
        String description = request.descriptionValue();
        return ResponseEntity.ok(TaskResponse.from(
                useCase.replaceTask(
                        id, request.title(), description, request.status())));
    }
}
