package com.diagnostic.agent.agent;

import com.diagnostic.agent.tool.MemoryProperties;
import com.diagnostic.agent.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryDiagnosisAgentTest {

    @Mock private com.diagnostic.agent.tool.ToolRegistry toolRegistry;
    @Mock private PromptService promptService;
    @Mock private LlmClient llmClient;
    @Mock private PromptContextBuilder promptContextBuilder;
    @Mock private DataSource dataSource;

    private com.diagnostic.agent.tool.MemoryUsageTool memoryUsageTool;
    private MemoryDiagnosisAgent agent;

    @BeforeEach
    void setup() {
        memoryUsageTool = spy(new com.diagnostic.agent.tool.MemoryUsageTool(dataSource, new MemoryProperties()));
        agent = new MemoryDiagnosisAgent(toolRegistry, promptService, llmClient, promptContextBuilder);
    }

    @Test
    void shouldReturnCorrectName() {
        assertThat(agent.getName()).isEqualTo("MemoryDiagnosisAgent");
    }

    @Test
    void shouldReturnMemoryKeywords() {
        assertThat(agent.getKeywords()).contains("内存", "缓存", "shared_buffers");
    }

    @Test
    void shouldAssignMemoryUsageTool() {
        assertThat(agent.assignedTools()).containsExactly("MemoryUsageTool");
    }

    @Test
    void shouldIncludeToolResultInLlmPrompt() {
        when(toolRegistry.get("MemoryUsageTool")).thenReturn(Optional.of(memoryUsageTool));
        when(promptService.loadTemplate(anyString())).thenReturn("You are a memory expert.");
        when(promptContextBuilder.buildContext(anyString())).thenReturn("");
        when(llmClient.chat(anyString(), anyString())).thenReturn("内存配置正常。");

        DiagnosisResult result = agent.diagnose(new DiagnosisContext("sess-001", "缓存命中率低"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAgentName()).isEqualTo("MemoryDiagnosisAgent");

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), userPromptCaptor.capture());
        assertThat(userPromptCaptor.getValue()).contains("MemoryUsageTool");
    }

    @Test
    void shouldExecuteAllToolsAlways() {
        assertThat(agent.shouldExecuteTool(memoryUsageTool, "任意问题")).isTrue();
    }

    @Test
    void shouldPassEmptyParametersToTool() {
        Map<String, Object> params = agent.buildToolParameters(memoryUsageTool, "任意问题");
        assertThat(params).isEmpty();
    }
}
