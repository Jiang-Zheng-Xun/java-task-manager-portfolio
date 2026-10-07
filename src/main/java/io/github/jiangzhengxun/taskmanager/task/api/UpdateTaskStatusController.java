package io.github.jiangzhengxun.taskmanager.task.api;

import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.UpdateTaskStatusUseCase;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/tasks")
public class UpdateTaskStatusController {

    private final UpdateTaskStatusUseCase useCase;

    public UpdateTaskStatusController(UpdateTaskStatusUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<TaskResponse> updateStatus(
            @PathVariable long id,
            @Valid @RequestBody UpdateTaskStatusRequest request) {
        return ResponseEntity.ok(TaskResponse.from(
                useCase.updateStatus(id, request.status())));
    }
}
