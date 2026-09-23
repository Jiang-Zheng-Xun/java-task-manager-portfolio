package io.github.jiangzhengxun.taskmanager.task.api;

import java.util.List;
import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.ListTasksUseCase;

@RestController
@RequestMapping("/api/tasks")
public class ListTasksController {

    private final ListTasksUseCase listTasksUseCase;

    public ListTasksController(ListTasksUseCase listTasksUseCase) {
        this.listTasksUseCase = Objects.requireNonNull(
                listTasksUseCase,
                "listTasksUseCase must not be null");
    }

    @GetMapping
    public ResponseEntity<List<TaskResponse>> listTasks() {
        List<TaskResponse> responses =
                listTasksUseCase.listTasks().stream()
                        .map(TaskResponse::from)
                        .toList();

        return ResponseEntity.ok(responses);
    }
}
