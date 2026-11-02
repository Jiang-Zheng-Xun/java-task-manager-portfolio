package io.github.jiangzhengxun.taskmanager.evidence.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.jiangzhengxun.taskmanager.task.api.CreateTaskRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class EvidenceRequestBodyConverterTest {

    private final ObjectMapper shared = new ObjectMapper();
    private final EvidenceRequestBodyConverter converter = new EvidenceRequestBodyConverter(shared.copy());

    @Test
    void readsOnlyTheExactEvidenceDtoAsJson() {
        assertThat(converter.canRead(CreateTaskEvidenceRequest.class, MediaType.APPLICATION_JSON)).isTrue();
        assertThat(converter.canRead(CreateTaskEvidenceRequest.class,
                MediaType.parseMediaType("application/json;charset=UTF-8"))).isTrue();
        assertThat(converter.canRead(CreateTaskEvidenceRequest.class, MediaType.TEXT_PLAIN)).isFalse();
        assertThat(converter.canRead(CreateTaskEvidenceRequest.class, null)).isFalse();
        assertThat(converter.canRead(Object.class, MediaType.APPLICATION_JSON)).isFalse();
        assertThat(converter.canRead(Record.class, MediaType.APPLICATION_JSON)).isFalse();
        assertThat(converter.canRead(CreateTaskRequest.class, MediaType.APPLICATION_JSON)).isFalse();
        assertThat(converter.canRead(String.class, MediaType.APPLICATION_JSON)).isFalse();
    }

    @Test
    void neverWrites() {
        assertThat(converter.canWrite(CreateTaskEvidenceRequest.class, MediaType.APPLICATION_JSON)).isFalse();
        assertThat(converter.canWrite(TaskEvidenceResponse.class, MediaType.APPLICATION_JSON)).isFalse();
        assertThat(converter.canWrite(Object.class, null)).isFalse();
    }

    @Test
    void sharedMapperIsNotMutated() {
        assertThat(shared.getDeserializationConfig()
                .isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)).isFalse();
        assertThat(shared.getFactory().isEnabled(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
                .isFalse();
    }
}
