package io.github.jiangzhengxun.taskmanager.task.api;

import java.util.List;
import java.util.Objects;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

import io.github.jiangzhengxun.taskmanager.task.application.port.in.ListTasksUseCase;
import io.github.jiangzhengxun.taskmanager.task.application.model.TaskPage;

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
    public ResponseEntity<List<TaskResponse>> listTasks(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "page must be non-negative and size must be between 1 and 100");
        }

        TaskPage result = listTasksUseCase.listTasks(page, size);
        List<TaskResponse> responses = result.tasks().stream()
                .map(TaskResponse::from)
                .toList();

        return ResponseEntity.ok()
                .header("X-Has-Next-Page", Boolean.toString(result.hasNext()))
                .body(responses);
    }
}
