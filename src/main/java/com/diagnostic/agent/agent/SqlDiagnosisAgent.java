package com.diagnostic.agent.agent;

import com.diagnostic.agent.config.DiagnosticMetrics;
import com.diagnostic.agent.tool.Tool;
import com.diagnostic.agent.tool.ToolRegistry;
import com.diagnostic.agent.trace.ExecutionTraceRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Order(1)
@Component
public class SqlDiagnosisAgent extends BaseExpertAgent {

    // containsSql() 专用：SQL 语法关键词，用于判断是否走 ExplainTool
    private static final List<String> SQL_KEYWORDS =
            List.of("SELECT", "INSERT", "UPDATE", "DELETE", "FROM", "WHERE", "EXPLAIN", "WITH");

    // Router 路由关键词：业务主题词（中英文、数据库名等）
    private static final List<String> ROUTING_KEYWORDS = List.of(
            "sql", "数据库", "查询", "索引", "执行计划", "慢查询",
            "postgres", "postgresql", "mysql",
            "select", "insert", "update", "delete"
    );

    public SqlDiagnosisAgent(ToolRegistry toolRegistry,
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
