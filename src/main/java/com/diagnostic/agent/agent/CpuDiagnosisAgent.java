package com.diagnostic.agent.agent;

import com.diagnostic.agent.tool.Tool;
import com.diagnostic.agent.tool.ToolRegistry;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Order(2)
@Component
public class CpuDiagnosisAgent extends BaseExpertAgent {

    private static final List<String> ROUTING_KEYWORDS = List.of(
            "cpu", "load", "utilization", "top", "high cpu",
            "负载", "CPU飙高", "CPU 100%", "CPU使用率", "CPU高", "cpu高"
    );

    public CpuDiagnosisAgent(ToolRegistry toolRegistry,
                             PromptService promptService,
                             LlmClient llmClient,
                             PromptContextBuilder promptContextBuilder) {
        super(toolRegistry, promptService, llmClient, promptContextBuilder);
    }

    @Override
    public List<String> getKeywords() {
        return ROUTING_KEYWORDS;
    }

    @Override
    protected List<String> assignedTools() {
        return List.of("CpuUsageTool");
    }

    @Override
    protected String getSystemPromptTemplateKey() {
        return PromptKeys.CPU_DIAGNOSIS_SYSTEM;
    }

    @Override
    public String getName() {
        return "CpuDiagnosisAgent";
    }

    @Override
    public String getDescription() {
        return "CPU资源诊断专家，分析系统负载和CPU使用率";
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
