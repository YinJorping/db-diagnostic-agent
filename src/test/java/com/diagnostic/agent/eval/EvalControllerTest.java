package com.diagnostic.agent.eval;

import com.diagnostic.agent.agent.AgentRouter;
import com.diagnostic.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EvalController.class)
class EvalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        EvalRunner evalRunner() {
            return mock(EvalRunner.class);
        }
    }

    @Autowired
    private EvalRunner evalRunner;

    @Test
    void shouldTriggerEvalRun() throws Exception {
        EvalRun stubRun = new EvalRun("sql", "AUTO", Map.of());
        when(evalRunner.start(anyString(), anyString(), any())).thenReturn(stubRun);

        String body = objectMapper.writeValueAsString(Map.of(
                "domain", "sql",
                "mode", "AUTO",
                "promptOverrides", Map.of()
        ));

        mockMvc.perform(post("/api/eval/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.runId").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("PENDING"));
    }

    @Test
    void shouldReturnNotFoundForUnknownRunId() throws Exception {
        when(evalRunner.getRun("unknown")).thenReturn(null);

        mockMvc.perform(get("/api/eval/report/unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4004));
    }

    @Test
    void shouldReturnRunningStatus() throws Exception {
        EvalRun running = new EvalRun("sql", "AUTO", Map.of());
        running.setRunning();
        when(evalRunner.getRun("running-1")).thenReturn(running);

        mockMvc.perform(get("/api/eval/report/running-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andExpect(jsonPath("$.data.message").value("Evaluation is still running"));
    }
}
