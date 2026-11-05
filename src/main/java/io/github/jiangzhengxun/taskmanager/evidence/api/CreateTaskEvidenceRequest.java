package io.github.jiangzhengxun.taskmanager.evidence.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * Evidence POST body. {@code content == null} means missing, JSON null or a non-string value; the controller
 * rejects it with "content must be a string" before calling the use case.
 */
@JsonDeserialize(using = CreateTaskEvidenceRequestDeserializer.class)
public record CreateTaskEvidenceRequest(String content) {
}
