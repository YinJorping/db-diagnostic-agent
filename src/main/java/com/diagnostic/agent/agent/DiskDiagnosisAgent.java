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

@Order(5)
@Component
public class DiskDiagnosisAgent extends BaseExpertAgent {

    private static final List<String> ROUTING_KEYWORDS = List.of(
            "disk", "磁盘", "io", "i/o", "存储", "空间", "容量",
            "数据目录", "tablespace", "pg_wal", "wal", "ssd",
            "空间不足", "磁盘满", "磁盘使用率", "disk usage"
    );

    public DiskDiagnosisAgent(ToolRegistry toolRegistry,
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
        return List.of("DiskUsageTool");
    }

    @Override
    protected String getSystemPromptTemplateKey() {
        return PromptKeys.DISK_DIAGNOSIS_SYSTEM;
    }

    @Override
    public String getName() {
        return "DiskDiagnosisAgent";
    }

    @Override
    public String getDescription() {
        return "磁盘诊断专家，分析数据目录空间使用和I/O负载";
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
