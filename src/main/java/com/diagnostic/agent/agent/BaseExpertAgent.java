package com.diagnostic.agent.agent;

import com.diagnostic.agent.tool.RiskLevel;
import com.diagnostic.agent.tool.Tool;
import com.diagnostic.agent.tool.ToolRegistry;
import com.diagnostic.agent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 专家 Agent 抽象基类——模板方法模式。
 * 子类只需声明 name / description / assignedTools / systemPromptKey，
 * 基类负责 Tool 执行、LLM 调用、Risk 聚合。
 */
public abstract class BaseExpertAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(BaseExpertAgent.class);

    protected final ToolRegistry toolRegistry;
    protected final PromptService promptService;
    protected final LlmClient llmClient;
    protected final PromptContextBuilder promptContextBuilder;

    protected BaseExpertAgent(ToolRegistry toolRegistry,
                              PromptService promptService,
                              LlmClient llmClient,
                              PromptContextBuilder promptContextBuilder) {
        this.toolRegistry = toolRegistry;
        this.promptService = promptService;
        this.llmClient = llmClient;
        this.promptContextBuilder = promptContextBuilder;
    }

    // ---- 子类必须实现 ----

    /** 声明本 Agent 需要的工具名称列表。 */
    protected abstract List<String> assignedTools();

    /** Prompt 模板 key（如 {@link PromptKeys#SQL_DIAGNOSIS_SYSTEM}）。 */
    protected abstract String getSystemPromptTemplateKey();

    // ---- 子类可选覆写 ----

    /** 判断某 Tool 在当前 problem 下是否应当执行。默认全部执行。 */
    protected boolean shouldExecuteTool(Tool tool, String problem) {
        return true;
    }

    /** 构建 Tool 执行参数。子类覆写以传递定制参数（SQL 文本、limit 等）。 */
    protected Map<String, Object> buildToolParameters(Tool tool, String problem) {
        return Collections.emptyMap();
    }

    // ---- 模板方法 ----

    @Override
    public DiagnosisResult diagnose(DiagnosisContext ctx) {
        long start = System.currentTimeMillis();
        try {
            String historyText = promptContextBuilder.buildContext(ctx.sessionId());

            List<Tool> tools = selectTools();
            List<ToolResult> toolResults = executeTools(tools, ctx.problem());
            String toolResultsText = formatToolResults(toolResults);
            String userPrompt = buildUserPrompt(ctx.problem(), toolResultsText, historyText);
            String systemPrompt = promptService.loadTemplate(getSystemPromptTemplateKey());
            String llmResponse = llmClient.chat(systemPrompt, userPrompt);
            RiskLevel risk = aggregateRisk(toolResults);

            long elapsed = System.currentTimeMillis() - start;
            return DiagnosisResult.success(getName(), llmResponse, llmResponse, risk, elapsed);
        } catch (Exception e) {
            log.error("Agent [{}] 诊断失败: sessionId={}, {}", getName(), ctx.sessionId(), e.getMessage(), e);
            long elapsed = System.currentTimeMillis() - start;
            return DiagnosisResult.failure(getName(), e.getMessage());
        }
    }

    // ---- Tool 选择 ----

    /** 从 ToolRegistry 按 assignedTools() 名称过滤。未知名称 skip + warn。 */
    protected List<Tool> selectTools() {
        List<Tool> selected = new ArrayList<>();
        for (String name : assignedTools()) {
            Optional<Tool> tool = toolRegistry.get(name);
            if (tool.isPresent()) {
                selected.add(tool.get());
            } else {
                log.warn("Agent [{}] 声明的 Tool [{}] 未在 ToolRegistry 中找到，已跳过", getName(), name);
            }
        }
        return selected;
    }

    // ---- Tool 执行 ----

    /** 执行所有 Tool，异常降级为 ToolResult.failure()。 */
    protected List<ToolResult> executeTools(List<Tool> tools, String problem) {
        List<ToolResult> results = new ArrayList<>();
        for (Tool tool : tools) {
            if (!shouldExecuteTool(tool, problem)) {
                log.debug("Agent [{}] 跳过 Tool [{}]", getName(), tool.getName());
                continue;
            }
            try {
                results.add(tool.execute(buildToolParameters(tool, problem)));
            } catch (Exception e) {
                log.warn("Agent [{}] Tool [{}] 执行异常: {}", getName(), tool.getName(), e.getMessage());
                results.add(ToolResult.failure(tool.getName(), e.getMessage()));
            }
        }
        return results;
    }

    // ---- 结果格式化 ----

    /** 将所有 Tool 结果拼接为 LLM 可读文本。 */
    protected String formatToolResults(List<ToolResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("诊断工具执行结果：\n\n");
        for (ToolResult r : results) {
            sb.append("--- ").append(r.getToolName()).append(" ---\n");
            if (r.isSuccess()) {
                sb.append("状态: 成功\n");
                sb.append("摘要: ").append(r.getSummary()).append("\n");
                sb.append("详情: ").append(r.getDetail()).append("\n");
            } else {
                sb.append("状态: 失败\n");
                sb.append("错误: ").append(r.getError()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 构建完整 User Prompt，前置历史上下文。 */
    protected String buildUserPrompt(String problem, String toolResultsText, String historyText) {
        return historyText
                + "用户问题: " + problem + "\n\n"
                + toolResultsText + "\n"
                + "请基于以上诊断工具的输出结果，给出诊断结论和优化建议。";
    }

    // ---- Risk 聚合 ----

    /** 基于 ToolResult.getRisk() 聚合风险等级。禁止解析 detail Map。 */
    protected RiskLevel aggregateRisk(List<ToolResult> results) {
        boolean hasHigh = false;
        boolean hasMedium = false;
        for (ToolResult r : results) {
            if (!r.isSuccess()) continue;
            RiskLevel risk = r.getRisk();
            if (risk == RiskLevel.HIGH) hasHigh = true;
            else if (risk == RiskLevel.MEDIUM) hasMedium = true;
        }
        if (hasHigh) return RiskLevel.HIGH;
        if (hasMedium) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }
}
