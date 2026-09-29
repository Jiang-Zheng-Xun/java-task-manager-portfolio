package io.github.jiangzhengxun.taskmanager.task.api;

import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.DeleteTaskUseCase;

@RestController
@RequestMapping("/api/tasks")
public class DeleteTaskController {

    private final DeleteTaskUseCase useCase;

    public DeleteTaskController(DeleteTaskUseCase useCase) {
        this.useCase = Objects.requireNonNull(
                useCase, "useCase must not be null");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable long id) {
        useCase.deleteTask(id);
        return ResponseEntity.noContent().build();
    }
}
