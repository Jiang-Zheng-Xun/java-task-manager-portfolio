package io.github.jiangzhengxun.taskmanager.task.api;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.jiangzhengxun.taskmanager.task.application.model.TaskPage;
import io.github.jiangzhengxun.taskmanager.task.application.port.in.CreateTaskUseCase;
import io.github.jiangzhengxun.taskmanager.task.application.port.in.ListTasksUseCase;

@WebMvcTest({
        CreateTaskController.class,
        ListTasksController.class
})
class HttpErrorBoundaryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateTaskUseCase createTaskUseCase;

    @MockitoBean
    private ListTasksUseCase listTasksUseCase;

    @Test
    void returnsSafe405AndPreservesAllowForUnsupportedMethod()
            throws Exception {
        mockMvc.perform(put("/api/tasks")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(
                        HttpHeaders.ALLOW, containsString("GET")))
                .andExpect(header().string(
                        HttpHeaders.ALLOW, containsString("POST")))
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"))
                .andExpect(jsonPath("$.message")
                        .value("HTTP method is not supported"))
                .andExpect(jsonPath("$.path").value("/api/tasks"))
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());

        verifyNoInteractions(createTaskUseCase, listTasksUseCase);
    }

    @Test
    void returnsSafe415WithoutCallingUseCaseForPlainTextBody()
            throws Exception {
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.TEXT_PLAIN)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("unsupported request body"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(415))
                .andExpect(jsonPath("$.error").value("Unsupported Media Type"))
                .andExpect(jsonPath("$.message")
                        .value("Request Content-Type is not supported"))
                .andExpect(jsonPath("$.path").value("/api/tasks"))
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.trace").doesNotExist());

        verifyNoInteractions(createTaskUseCase, listTasksUseCase);
    }

    @Test
    void returns406WithoutForcingJsonForXmlAccept()
            throws Exception {
        when(listTasksUseCase.listTasks(0, 20))
                .thenReturn(new TaskPage(List.of(), false));

        mockMvc.perform(get("/api/tasks")
                        .accept(MediaType.APPLICATION_XML))
                .andExpect(status().isNotAcceptable())
                .andExpect(result -> assertEquals(
                        "",
                        result.getResponse().getContentAsString(),
                        "406 should have no body when XML cannot be produced"));
    }
}
