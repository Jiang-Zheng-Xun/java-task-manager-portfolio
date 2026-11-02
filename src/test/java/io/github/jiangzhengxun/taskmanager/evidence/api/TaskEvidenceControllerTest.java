package io.github.jiangzhengxun.taskmanager.evidence.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.jiangzhengxun.taskmanager.evidence.application.port.in.CreateTaskEvidenceUseCase;
import io.github.jiangzhengxun.taskmanager.evidence.application.port.in.ListTaskEvidenceUseCase;
import io.github.jiangzhengxun.taskmanager.evidence.application.port.out.TaskEvidencePage;
import io.github.jiangzhengxun.taskmanager.evidence.domain.TaskEvidence;
import io.github.jiangzhengxun.taskmanager.task.application.exception.TaskNotFoundException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TaskEvidenceController.class)
@Import(EvidenceWebMvcConfiguration.class)
class TaskEvidenceControllerTest {

    private static final String URL = "/api/tasks/1/evidence";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateTaskEvidenceUseCase createUseCase;

    @MockitoBean
    private ListTaskEvidenceUseCase listUseCase;

    private static TaskEvidence saved() {
        return TaskEvidence.restore(10L, 1L, "proof", Instant.parse("2026-11-01T07:00:00.123456Z"));
    }

    @Test
    void createReturns201WithExactlyFourFieldsAndNoLocation() throws Exception {
        when(createUseCase.createEvidence(1L, " proof ")).thenReturn(saved());
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\" proof \"}"))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Location"))
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.taskId").value(1))
                .andExpect(jsonPath("$.content").value("proof"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-01T07:00:00.123456Z"));
    }

    /** Each case is a distinct JSON spelling; all must be rejected before the use case is called. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"content\":\"a\",\"other\":1}",
            "{\"other\":1}",
            "{\"content\":\"a\",\"content\":\"b\"}",
            "{\"content\":\"a\",\"\\u0063ontent\":\"b\"}",
            "{\"\\u0063\\u006f\\u006e\\u0074\\u0065\\u006e\\u0074\":\"a\",\"content\":\"b\"}",
            "{\"content\":1,\"content\":\"b\"}",
            "{\"content\":\"a\"} {}",
            "{\"content\":\"a\"} x",
            "{\"content\":\"a\"}]",
            "[{\"content\":\"a\"}]",
            "\"a\"",
            "1",
            "null",
            "{\"content\":\"a\"",
            "{content:\"a\"}",
            ""
    })
    void structuralProblemsAreMalformedJson(String body) throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed JSON request"))
                .andExpect(jsonPath("$.path").value(URL));
        verifyNoInteractions(createUseCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"content\":null}", "{\"content\":1}", "{\"content\":true}",
            "{\"content\":[\"a\"]}", "{\"content\":{\"a\":\"b\"}}"})
    void missingNullOrNonStringContentIsRejectedAtTheBoundary(String body) throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("content must be a string"))
                .andExpect(jsonPath("$.path").value(URL));
        verifyNoInteractions(createUseCase);
    }

    @Test
    void contentRuleViolationFromUseCaseIsSafe400() throws Exception {
        when(createUseCase.createEvidence(1L, " ")).thenThrow(new IllegalArgumentException("content must not be blank"));
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("content must not be blank"));
    }

    @Test
    void errorBodyDoesNotEchoContentOrJacksonDetail() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"SECRET-123\",\"x\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("SECRET-123"))))
                .andExpect(content().string(not(containsString("Unrecognized"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "99999999999999999999"})
    void nonIntegerIdIs400(String id) throws Exception {
        mockMvc.perform(get("/api/tasks/" + id + "/evidence"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("id must be a valid integer"));
        verifyNoInteractions(listUseCase);
    }

    @Test
    void listReturnsArrayAndNextHeaderWithDefaults() throws Exception {
        when(listUseCase.listEvidence(1L, 0, 20)).thenReturn(new TaskEvidencePage(List.of(saved()), true));
        mockMvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Has-Next-Page", "true"))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].length()").value(4));
    }

    @Test
    void listNotFoundIs404() throws Exception {
        when(listUseCase.listEvidence(1L, 0, 20)).thenThrow(new TaskNotFoundException(1L));
        mockMvc.perform(get(URL)).andExpect(status().isNotFound());
    }

    @Test
    void nonNumericPageIs400() throws Exception {
        mockMvc.perform(get(URL).param("page", "x")).andExpect(status().isBadRequest());
        verifyNoInteractions(listUseCase);
    }

    @Test
    void unsupportedContentTypeIs415() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.TEXT_PLAIN).content("{\"content\":\"a\"}"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(createUseCase);
    }

    @Test
    void unsupportedMethodIs405() throws Exception {
        mockMvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void notAcceptableIs406WithEmptyBody() throws Exception {
        mockMvc.perform(get(URL).accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().string(""));
        verifyNoInteractions(listUseCase);
    }
}
