package com.diagnostic.agent.agent;

import com.diagnostic.agent.tool.CpuMetricsProvider;
import com.diagnostic.agent.tool.RiskLevel;
import com.diagnostic.agent.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.spy;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CpuDiagnosisAgentTest {

    @Mock private com.diagnostic.agent.tool.ToolRegistry toolRegistry;
    @Mock private PromptService promptService;
    @Mock private LlmClient llmClient;
    @Mock private PromptContextBuilder promptContextBuilder;
    @Mock private CpuMetricsProvider cpuMetricsProvider;
    @Mock private com.diagnostic.agent.trace.ExecutionTraceRepository traceRepository;
    private final com.diagnostic.agent.config.DiagnosticMetrics metrics =
            new com.diagnostic.agent.config.DiagnosticMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

    private com.diagnostic.agent.tool.CpuUsageTool cpuUsageTool;
    private CpuDiagnosisAgent agent;

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        cpuUsageTool = spy(new com.diagnostic.agent.tool.CpuUsageTool(cpuMetricsProvider,
                new com.diagnostic.agent.tool.CpuProperties()));
        agent = new CpuDiagnosisAgent(toolRegistry, promptService, llmClient, promptContextBuilder,
                traceRepository, metrics);
    }

    @Test
    void shouldReturnCorrectName() {
        assertThat(agent.getName()).isEqualTo("CpuDiagnosisAgent");
    }

    @Test
    void shouldReturnCpuKeywords() {
        assertThat(agent.getKeywords()).contains("cpu", "负载", "CPU飙高");
    }

    @Test
    void shouldAssignCpuUsageTool() {
        assertThat(agent.assignedTools()).containsExactly("CpuUsageTool");
    }

    @Test
    void shouldIncludeToolResultInLlmPrompt() {
        when(toolRegistry.get("CpuUsageTool")).thenReturn(Optional.of(cpuUsageTool));
        when(cpuMetricsProvider.sample()).thenReturn(
                new com.diagnostic.agent.tool.CpuMetrics(0.3, 0.2, 2.0, 8));
        when(promptService.loadTemplate(anyString())).thenReturn("You are a CPU expert.");
        when(promptContextBuilder.buildContext(anyString())).thenReturn("");
        when(llmClient.chat(anyString(), anyString())).thenReturn("CPU 资源正常。");

        DiagnosisResult result = agent.diagnose(new DiagnosisContext("sess-001", "CPU 100%"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAgentName()).isEqualTo("CpuDiagnosisAgent");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), userPromptCaptor.capture());
        assertThat(userPromptCaptor.getValue()).contains("CpuUsageTool");
    }

    @Test
    void shouldExecuteAllToolsAlways() {
        assertThat(agent.shouldExecuteTool(cpuUsageTool, "任意问题")).isTrue();
    }

    @Test
    void shouldPassEmptyParametersToTool() {
        Map<String, Object> params = agent.buildToolParameters(cpuUsageTool, "任意问题");
        assertThat(params).isEmpty();
    }
}
