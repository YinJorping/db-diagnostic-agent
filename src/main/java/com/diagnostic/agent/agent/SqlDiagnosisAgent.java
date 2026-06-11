package com.diagnostic.agent.agent;

import com.diagnostic.agent.tool.Tool;
import com.diagnostic.agent.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SqlDiagnosisAgent extends BaseExpertAgent {

    private static final List<String> SQL_KEYWORDS =
            List.of("SELECT", "INSERT", "UPDATE", "DELETE", "FROM", "WHERE", "EXPLAIN", "WITH");

    public SqlDiagnosisAgent(ToolRegistry toolRegistry,
                             PromptService promptService,
                             LlmClient llmClient) {
        super(toolRegistry, promptService, llmClient);
    }

    @Override
    protected List<String> assignedTools() {
        return List.of("ExplainTool", "SlowQueryTool");
    }

    @Override
    protected String getSystemPromptTemplateKey() {
        return PromptKeys.SQL_DIAGNOSIS_SYSTEM;
    }

    @Override
    public String getName() {
        return "SqlDiagnosisAgent";
    }

    @Override
    public String getDescription() {
        return "SQL性能诊断专家，分析执行计划和慢查询日志";
    }

    @Override
    protected boolean shouldExecuteTool(Tool tool, String problem) {
        if ("ExplainTool".equals(tool.getName())) {
            return containsSql(problem);
        }
        return true;
    }

    @Override
    protected Map<String, Object> buildToolParameters(Tool tool, String problem) {
        if ("ExplainTool".equals(tool.getName())) {
            return Map.of("sql", problem);
        }
        if ("SlowQueryTool".equals(tool.getName())) {
            return Map.of("limit", 10);
        }
        return Map.of();
    }

    boolean containsSql(String problem) {
        if (problem == null || problem.isBlank()) return false;
        String upper = problem.toUpperCase();
        return SQL_KEYWORDS.stream().anyMatch(upper::contains);
    }
}
