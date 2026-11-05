package io.github.jiangzhengxun.taskmanager.evidence.api;

import io.github.jiangzhengxun.taskmanager.evidence.application.port.in.CreateTaskEvidenceUseCase;
import io.github.jiangzhengxun.taskmanager.evidence.application.port.in.ListTaskEvidenceUseCase;
import io.github.jiangzhengxun.taskmanager.evidence.application.port.out.TaskEvidencePage;
import io.github.jiangzhengxun.taskmanager.evidence.domain.EvidenceContent;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** id/page/size and content rules live in the use cases / domain; the controller only rejects non-string content. */
@RestController
@RequestMapping("/api/tasks/{id}/evidence")
public class TaskEvidenceController {

    private final CreateTaskEvidenceUseCase createUseCase;
    private final ListTaskEvidenceUseCase listUseCase;

    public TaskEvidenceController(CreateTaskEvidenceUseCase createUseCase, ListTaskEvidenceUseCase listUseCase) {
        this.createUseCase = Objects.requireNonNull(createUseCase, "createUseCase must not be null");
        this.listUseCase = Objects.requireNonNull(listUseCase, "listUseCase must not be null");
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TaskEvidenceResponse> create(
            @PathVariable long id, @RequestBody CreateTaskEvidenceRequest request) {
        // API boundary: missing / null / non-string content is rejected before the use case is called.
        // Service and domain keep their own validation for direct callers.
        String content = request.content();
        if (content == null) {
            throw new IllegalArgumentException(EvidenceContent.NOT_STRING);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TaskEvidenceResponse.from(createUseCase.createEvidence(id, content)));
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<TaskEvidenceResponse>> list(
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        TaskEvidencePage result = listUseCase.listEvidence(id, page, size);
        return ResponseEntity.ok()
                .header("X-Has-Next-Page", Boolean.toString(result.hasNext()))
                .body(result.evidence().stream().map(TaskEvidenceResponse::from).toList());
    }
}
