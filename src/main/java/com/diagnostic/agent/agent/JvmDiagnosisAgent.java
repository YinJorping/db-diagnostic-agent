package com.diagnostic.agent.agent;

import com.diagnostic.agent.config.DiagnosticMetrics;
import com.diagnostic.agent.tool.Tool;
import com.diagnostic.agent.tool.ToolRegistry;
import com.diagnostic.agent.trace.ExecutionTraceRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Order(4)
@Component
public class JvmDiagnosisAgent extends BaseExpertAgent {

    private static final List<String> ROUTING_KEYWORDS = List.of(
            "jvm", "heap", "gc", "full gc", "young gc", "old gc",
            "metaspace", "oom", "堆", "垃圾回收", "元空间"
    );

    public JvmDiagnosisAgent(ToolRegistry toolRegistry,
                             PromptService promptService,
                             LlmClient llmClient,
                             PromptContextBuilder promptContextBuilder,
                             ExecutionTraceRepository traceRepository,
                             DiagnosticMetrics metrics) {
        super(toolRegistry, promptService, llmClient, promptContextBuilder, traceRepository, metrics);
    }

    @Override
    public List<String> getKeywords() {
        return ROUTING_KEYWORDS;
    }

    @Override
    protected List<String> assignedTools() {
        return List.of("JvmUsageTool");
    }

    @Override
    protected String getSystemPromptTemplateKey() {
        return PromptKeys.JVM_DIAGNOSIS_SYSTEM;
    }

    @Override
    public String getName() {
        return "JvmDiagnosisAgent";
    }

    @Override
    public String getDescription() {
        return "JVM诊断专家，分析堆内存、GC行为和线程资源";
    }

    @Override
    protected boolean shouldExecuteTool(Tool tool, String problem) {
        return true;
    }

    @Override
    protected Map<String, Object> buildToolParameters(Tool tool, String problem) {
        return Map.of();
    }
}
