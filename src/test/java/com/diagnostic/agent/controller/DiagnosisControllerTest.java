package com.diagnostic.agent.controller;

import com.diagnostic.agent.agent.DiagnosisReport;
import com.diagnostic.agent.agent.DiagnosisResult;
import com.diagnostic.agent.agent.OrchestratorAgent;
import com.diagnostic.agent.tool.RiskLevel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DiagnosisController.class)
class DiagnosisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OrchestratorAgent orchestrator;

    // ---- Scenario 1: 正常请求 ----

    @Test
    void shouldReturnDiagnosisResponseOnValidRequest() throws Exception {
        DiagnosisResult r = DiagnosisResult.success(
                "SqlDiagnosisAgent", "检测到全表扫描", "建议加索引",
                RiskLevel.HIGH, 150L);
        DiagnosisReport mockReport = DiagnosisReport.fromSingle("sess-001", r);

        when(orchestrator.diagnose("sess-001", "数据库查询很慢")).thenReturn(mockReport);

        mockMvc.perform(post("/api/diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"sess-001","problem":"数据库查询很慢"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.sessionId").value("sess-001"))
                .andExpect(jsonPath("$.data.agentName").value("SqlDiagnosisAgent"))
                .andExpect(jsonPath("$.data.summary").value("检测到全表扫描"))
                .andExpect(jsonPath("$.data.risk").value("HIGH"))
                .andExpect(jsonPath("$.data.agentCount").value(1));
    }

    // ---- Scenario 2: sessionId 为空 ----

    @Test
    void shouldReturn400WhenSessionIdEmpty() throws Exception {
        mockMvc.perform(post("/api/diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"","problem":"数据库查询很慢"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4000));
    }

    // ---- Scenario 3: problem 为空 ----

    @Test
    void shouldReturn400WhenProblemEmpty() throws Exception {
        mockMvc.perform(post("/api/diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"sess-001","problem":""}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(4000));
    }

    // ---- Scenario 4: Agent 抛异常 ----

    @Test
    void shouldReturnErrorWhenAgentThrows() throws Exception {
        when(orchestrator.diagnose(anyString(), anyString()))
                .thenThrow(new RuntimeException("Agent 内部错误"));

        mockMvc.perform(post("/api/diagnose")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sessionId":"sess-001","problem":"SELECT 1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(5000));
    }
}
