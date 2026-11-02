package io.github.jiangzhengxun.taskmanager.evidence.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Consumes exactly its own object and stops on its own END_OBJECT. Trailing tokens and parser-level
 * duplicate detection are handled by the dedicated ObjectReader in {@link EvidenceRequestBodyConverter}.
 * Field names are compared after JSON unescaping, so a duplicate spelled with escapes is also rejected here.
 */
final class CreateTaskEvidenceRequestDeserializer extends StdDeserializer<CreateTaskEvidenceRequest> {

    CreateTaskEvidenceRequestDeserializer() {
        super(CreateTaskEvidenceRequest.class);
    }

    @Override
    public CreateTaskEvidenceRequest deserialize(JsonParser parser, DeserializationContext context)
            throws IOException {
        if (!parser.isExpectedStartObjectToken()) {
            throw JsonMappingException.from(parser, "expected object");
        }
        Set<String> seen = new HashSet<>();
        String content = null;
        JsonToken token;
        while ((token = parser.nextToken()) == JsonToken.FIELD_NAME) {
            String name = parser.currentName();
            if (!seen.add(name) || !"content".equals(name)) {
                throw JsonMappingException.from(parser, "unexpected field");
            }
            JsonToken value = parser.nextToken();
            if (value == JsonToken.VALUE_STRING) {
                content = parser.getText();
            } else {
                parser.skipChildren();
            }
        }
        if (token != JsonToken.END_OBJECT) {
            throw JsonMappingException.from(parser, "expected end of object");
        }
        return new CreateTaskEvidenceRequest(content);
    }
}
