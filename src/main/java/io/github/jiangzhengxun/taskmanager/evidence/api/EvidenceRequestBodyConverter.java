package io.github.jiangzhengxun.taskmanager.evidence.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

/**
 * Read-only converter for exactly {@link CreateTaskEvidenceRequest} with application/json. It is a plain
 * (non-generic) HttpMessageConverter, so {@link #canRead(Class, MediaType)} is the only read entry point.
 * Never writes. Uses an ObjectReader derived from an isolated mapper copy; the shared mapper is not mutated.
 */
final class EvidenceRequestBodyConverter implements HttpMessageConverter<CreateTaskEvidenceRequest> {

    private static final List<MediaType> SUPPORTED = List.of(MediaType.APPLICATION_JSON);

    private final ObjectReader reader;

    EvidenceRequestBodyConverter(ObjectMapper isolatedMapper) {
        this.reader = Objects.requireNonNull(isolatedMapper, "isolatedMapper must not be null")
                .readerFor(CreateTaskEvidenceRequest.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return clazz == CreateTaskEvidenceRequest.class
                && mediaType != null
                && MediaType.APPLICATION_JSON.includes(mediaType);
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return false;
    }

    @Override
    public List<MediaType> getSupportedMediaTypes() {
        return SUPPORTED;
    }

    @Override
    public List<MediaType> getSupportedMediaTypes(Class<?> clazz) {
        return clazz == CreateTaskEvidenceRequest.class ? SUPPORTED : List.of();
    }

    @Override
    public CreateTaskEvidenceRequest read(Class<? extends CreateTaskEvidenceRequest> clazz,
            HttpInputMessage inputMessage) throws IOException {
        try {
            return reader.readValue(inputMessage.getBody());
        } catch (IOException exception) {
            // Fixed message; Jackson's original text (which may echo input) is not propagated.
            throw new HttpMessageNotReadableException("Malformed JSON request", inputMessage);
        }
    }

    @Override
    public void write(CreateTaskEvidenceRequest value, MediaType contentType, HttpOutputMessage outputMessage)
            throws HttpMessageNotWritableException {
        throw new HttpMessageNotWritableException("EvidenceRequestBodyConverter is read-only");
    }
}
