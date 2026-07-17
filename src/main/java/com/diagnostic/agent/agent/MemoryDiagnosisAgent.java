package com.diagnostic.agent.agent;

import com.diagnostic.agent.common.security.SensitiveDataMasker;
import com.diagnostic.agent.config.DiagnosticMetrics;
import com.diagnostic.agent.tool.Tool;
import com.diagnostic.agent.tool.ToolRegistry;
import com.diagnostic.agent.trace.ExecutionTraceRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Order(3)
@Component
public class MemoryDiagnosisAgent extends BaseExpertAgent {

    private static final List<String> ROUTING_KEYWORDS = List.of(
            "内存", "memory", "缓存", "buffer", "shared_buffers",
            "work_mem", "命中率", "缓存命中", "temp_files",
            "内存不足", "内存高", "缓存不够"
    );

    public MemoryDiagnosisAgent(ToolRegistry toolRegistry,
                                PromptService promptService,
                                LlmClient llmClient,
                                PromptContextBuilder promptContextBuilder,
                                ExecutionTraceRepository traceRepository,
                                DiagnosticMetrics metrics,
                                SensitiveDataMasker sensitiveDataMasker) {
        super(toolRegistry, promptService, llmClient, promptContextBuilder, traceRepository, metrics, sensitiveDataMasker);
    }

    @Override
    public List<String> getKeywords() {
        return ROUTING_KEYWORDS;
    }

    @Override
    protected List<String> assignedTools() {
        return List.of("MemoryUsageTool");
    }

    @Override
    protected String getSystemPromptTemplateKey() {
        return PromptKeys.MEMORY_DIAGNOSIS_SYSTEM;
    }

    @Override
    public String getName() {
        return "MemoryDiagnosisAgent";
    }

    @Override
    public String getDescription() {
        return "内存诊断专家，分析数据库缓存命中率和内存配置";
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
