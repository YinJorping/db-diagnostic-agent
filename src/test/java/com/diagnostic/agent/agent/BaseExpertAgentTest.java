package com.diagnostic.agent.agent;

import com.diagnostic.agent.config.DiagnosticMetrics;
import com.diagnostic.agent.memory.MessageType;
import com.diagnostic.agent.memory.StoredMessage;
import com.diagnostic.agent.tool.RiskLevel;
import com.diagnostic.agent.tool.Tool;
import com.diagnostic.agent.tool.ToolRegistry;
import com.diagnostic.agent.tool.ToolResult;
import com.diagnostic.agent.trace.ExecutionTrace;
import com.diagnostic.agent.trace.ExecutionTraceRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BaseExpertAgentTest {

    @Mock private ToolRegistry toolRegistry;
    @Mock private PromptService promptService;
    @Mock private LlmClient llmClient;
    @Mock private PromptContextBuilder promptContextBuilder;
    @Mock private ExecutionTraceRepository traceRepository;
    private final DiagnosticMetrics metrics = new DiagnosticMetrics(new SimpleMeterRegistry());

    private TestAgent agent;
    private ExecutionTrace trace;

    @BeforeEach
    void setup() {
        agent = new TestAgent(toolRegistry, promptService, llmClient, promptContextBuilder,
                traceRepository, metrics);
        trace = new ExecutionTrace("test-trace", "TestAgent", "test-session", 0);
    }

    // ---- 1. Tool 过滤：按名选择，跳过不存在的 ----

    @Test
    void shouldSelectOnlyAssignedTools() {
        Tool tool1 = dummyTool("t1");
        when(toolRegistry.get("t1")).thenReturn(Optional.of(tool1));
        when(toolRegistry.get("t2")).thenReturn(Optional.empty());

        agent.setAssignedTools(List.of("t1", "t2"));

        List<Tool> selected = agent.selectTools();

        assertThat(selected).hasSize(1);
        assertThat(selected.get(0).getName()).isEqualTo("t1");
    }

    // ---- 2. Tool 异常降级：异常捕获为 ToolResult.failure ----

    @Test
    void shouldDegradeToolExceptionToFailure() {
        Tool badTool = dummyThrowingTool("bad");
        agent.setAssignedTools(List.of("bad"));
        when(toolRegistry.get("bad")).thenReturn(Optional.of(badTool));

        List<ToolResult> results = agent.executeTools(agent.selectTools(), "test", trace);

        assertThat(results).hasSize(1);
        ToolResult result = results.get(0);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("模拟工具异常");
        assertThat(result.getRisk()).isEqualTo(RiskLevel.UNKNOWN);
    }

    // ---- 3. aggregateRisk — HIGH > MEDIUM > LOW ----

    @Test
    void shouldAggregateToHighWhenAnyHigh() {
        List<ToolResult> results = List.of(
                ToolResult.success("t1", "s", Map.of(), RiskLevel.LOW, 1),
                ToolResult.success("t2", "s", Map.of(), RiskLevel.HIGH, 1),
                ToolResult.success("t3", "s", Map.of(), RiskLevel.MEDIUM, 1));

        assertThat(agent.aggregateRisk(results)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void shouldAggregateToMediumWhenNoHigh() {
        List<ToolResult> results = List.of(
                ToolResult.success("t1", "s", Map.of(), RiskLevel.LOW, 1),
                ToolResult.success("t2", "s", Map.of(), RiskLevel.MEDIUM, 1));

        assertThat(agent.aggregateRisk(results)).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void shouldAggregateToLowWhenNoFindings() {
        List<ToolResult> results = List.of(
                ToolResult.success("t1", "s", Map.of(), RiskLevel.LOW, 1));

        assertThat(agent.aggregateRisk(results)).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void shouldSkipFailedToolsInAggregation() {
        List<ToolResult> results = List.of(
                ToolResult.failure("t1", "error"),
                ToolResult.success("t2", "s", Map.of(), RiskLevel.LOW, 1));

        assertThat(agent.aggregateRisk(results)).isEqualTo(RiskLevel.LOW);
    }

    // ---- 4. 空 Tool 列表：返回 LOW ----

    @Test
    void shouldReturnLowForEmptyToolList() {
        agent.setAssignedTools(List.of());
        when(promptContextBuilder.buildContext(anyString())).thenReturn("");
        when(promptService.loadTemplate(anyString())).thenReturn("system prompt");
        when(llmClient.chat(anyString(), anyString())).thenReturn("无工具可用");

        DiagnosisResult result = agent.diagnose(new DiagnosisContext("sess-1", "test"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.LOW);
        assertThat(result.getSummary()).contains("无工具可用");
    }

    // ---- 5. shouldExecuteTool = false：跳过特定 Tool ----

    @Test
    void shouldSkipToolWhenShouldExecuteReturnsFalse() {
        Tool tool1 = dummyTool("t1");
        Tool tool2 = dummyTool("t2");
        agent.setAssignedTools(List.of("t1", "t2"));
        when(toolRegistry.get("t1")).thenReturn(Optional.of(tool1));
        when(toolRegistry.get("t2")).thenReturn(Optional.of(tool2));

        // 覆写 shouldExecuteTool 跳过 t2
        agent.setSkipTool("t2");

        List<ToolResult> results = agent.executeTools(agent.selectTools(), "test", trace);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getToolName()).isEqualTo("t1");
    }

    // ---- 6. buildToolParameters 参数传递 ----

    @Test
    void shouldPassBuildToolParametersToToolExecute() {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        Tool paramTool = new Tool() {
            @Override public String getName() { return "paramTool"; }
            @Override public String getDescription() { return "captures params"; }
            @Override
            public ToolResult execute(Map<String, Object> params) {
                captured.set(params);
                return ToolResult.success("paramTool", "ok", Map.of(), RiskLevel.LOW, 0);
            }
        };

        agent.setAssignedTools(List.of("paramTool"));
        agent.setToolParams(Map.of("customKey", "customValue"));
        when(toolRegistry.get("paramTool")).thenReturn(Optional.of(paramTool));

        agent.executeTools(agent.selectTools(), "test", trace);

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get()).containsEntry("customKey", "customValue");
    }

    // ---- 7. 历史注入 ----

    @Test
    void shouldIncludeHistoryInUserPrompt() {
        List<StoredMessage> history = List.of(
                StoredMessage.of(MessageType.USER, "数据库很慢"),
                StoredMessage.of(MessageType.ASSISTANT, "怀疑缺少索引")
        );
        agent.setAssignedTools(List.of());
        when(promptContextBuilder.buildContext("sess-1")).thenReturn(
                "=== 历史会话 ===\n用户: 数据库很慢\n助手: 怀疑缺少索引\n");
        when(promptService.loadTemplate(anyString())).thenReturn("system");
        when(llmClient.chat(anyString(), anyString())).thenReturn("诊断结果");

        agent.diagnose(new DiagnosisContext("sess-1", "CPU高"));

        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), userPromptCaptor.capture());
        String userPrompt = userPromptCaptor.getValue();
        assertThat(userPrompt).contains("=== 历史会话 ===");
        assertThat(userPrompt).contains("用户: 数据库很慢");
        assertThat(userPrompt).contains("助手: 怀疑缺少索引");
        assertThat(userPrompt).contains("CPU高");
    }

    // ---- 8. 空历史 ----

    @Test
    void shouldHandleEmptyHistory() {
        agent.setAssignedTools(List.of());
        when(promptContextBuilder.buildContext("sess-2")).thenReturn("");
        when(promptService.loadTemplate(anyString())).thenReturn("system");
        when(llmClient.chat(anyString(), anyString())).thenReturn("诊断结果");

        DiagnosisResult result = agent.diagnose(new DiagnosisContext("sess-2", "test"));

        assertThat(result.isSuccess()).isTrue();
        ArgumentCaptor<String> userPromptCaptor = ArgumentCaptor.forClass(String.class);
        verify(llmClient).chat(anyString(), userPromptCaptor.capture());
        String userPrompt = userPromptCaptor.getValue();
        assertThat(userPrompt).doesNotContain("=== 历史会话 ===");
    }

    // ---- Helpers ----

    private static Tool dummyThrowingTool(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "throws"; }
            @Override
            public ToolResult execute(Map<String, Object> params) {
                throw new RuntimeException("模拟工具异常");
            }
        };
    }

    private static Tool dummyTool(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "mock"; }
            @Override
            public ToolResult execute(Map<String, Object> params) {
                return ToolResult.success(name, "ok", Map.of(), RiskLevel.LOW, 0);
            }
        };
    }

    /** 测试用 Agent 子类。 */
    static class TestAgent extends BaseExpertAgent {
        private List<String> assignedTools = List.of();
        private String skipToolName;
        private Map<String, Object> toolParams;

        TestAgent(ToolRegistry registry, PromptService prompts, LlmClient llm,
                  PromptContextBuilder pcb, ExecutionTraceRepository traceRepo, DiagnosticMetrics m) {
            super(registry, prompts, llm, pcb, traceRepo, m);
        }

        void setAssignedTools(List<String> names) { this.assignedTools = names; }
        void setSkipTool(String name) { this.skipToolName = name; }
        void setToolParams(Map<String, Object> params) { this.toolParams = params; }

        @Override protected List<String> assignedTools() { return assignedTools; }
        @Override protected String getSystemPromptTemplateKey() { return "test_prompt"; }
        @Override public String getName() { return "TestAgent"; }
        @Override public String getDescription() { return "测试Agent"; }

        @Override
        protected boolean shouldExecuteTool(Tool tool, String problem) {
            if (tool.getName().equals(skipToolName)) return false;
            return true;
        }

        @Override
        protected Map<String, Object> buildToolParameters(Tool tool, String problem) {
            return toolParams != null ? toolParams : Map.of();
        }
    }
}
