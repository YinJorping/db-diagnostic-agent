package com.diagnostic.agent.agent;

import com.diagnostic.agent.tool.JvmMetricsProvider;
import com.diagnostic.agent.tool.JvmProperties;
import com.diagnostic.agent.tool.JvmUsageTool;
import com.diagnostic.agent.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JvmDiagnosisAgentTest {

    @Mock private com.diagnostic.agent.tool.ToolRegistry toolRegistry;
    @Mock private PromptService promptService;
    @Mock private LlmClient llmClient;
    @Mock private PromptContextBuilder promptContextBuilder;
    @Mock private JvmMetricsProvider jvmMetricsProvider;

    private JvmUsageTool jvmUsageTool;
    private JvmDiagnosisAgent agent;

    @BeforeEach
    void setup() {
        jvmUsageTool = spy(new JvmUsageTool(jvmMetricsProvider, new JvmProperties()));
        agent = new JvmDiagnosisAgent(toolRegistry, promptService, llmClient, promptContextBuilder);
    }

    @Test
    void shouldReturnCorrectName() {
        assertThat(agent.getName()).isEqualTo("JvmDiagnosisAgent");
    }

    @Test
    void shouldReturnJvmKeywords() {
        assertThat(agent.getKeywords()).contains("jvm", "heap", "gc", "metaspace", "oom");
    }

    @Test
    void shouldNotContainOverlappingKeywords() {
        assertThat(agent.getKeywords()).doesNotContain("线程", "thread", "内存");
    }

    @Test
    void shouldAssignJvmUsageTool() {
        assertThat(agent.assignedTools()).containsExactly("JvmUsageTool");
    }

    @Test
    void shouldIncludeToolResultInLlmPrompt() {
        when(toolRegistry.get("JvmUsageTool")).thenReturn(Optional.of(jvmUsageTool));
        when(jvmMetricsProvider.sample()).thenReturn(
                new com.diagnostic.agent.tool.JvmMetrics(
                        500L * 1024 * 1024, 1024L * 1024 * 1024,
                        100L * 1024 * 1024, 200L * 1024 * 1024,
                        java.util.List.of(), 50, 100, 30, 60_000));
        when(promptService.loadTemplate(anyString())).thenReturn("You are a JVM expert.");
        when(promptContextBuilder.buildContext(anyString())).thenReturn("");
        when(llmClient.chat(anyString(), anyString())).thenReturn("JVM 资源正常。");

        DiagnosisResult result = agent.diagnose(new DiagnosisContext("sess-001", "JVM 堆内存高"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAgentName()).isEqualTo("JvmDiagnosisAgent");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), userPromptCaptor.capture());
        assertThat(userPromptCaptor.getValue()).contains("JvmUsageTool");
    }

    @Test
    void shouldExecuteAllToolsAlways() {
        assertThat(agent.shouldExecuteTool(jvmUsageTool, "任意问题")).isTrue();
    }

    @Test
    void shouldPassEmptyParametersToTool() {
        Map<String, Object> params = agent.buildToolParameters(jvmUsageTool, "任意问题");
        assertThat(params).isEmpty();
    }
}
