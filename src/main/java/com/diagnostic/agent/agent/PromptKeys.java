package com.diagnostic.agent.agent;

/**
 * Prompt Template Key 常量。
 * 管理所有 {@code prompt_template.template_key} 值，避免硬编码字符串。
 */
public final class PromptKeys {

    public static final String SQL_DIAGNOSIS_SYSTEM = "sql_diagnosis_system";
    public static final String SQL_EXPLAIN_ANALYSIS = "sql_explain_analysis";
    public static final String ORCHESTRATOR_ROUTER = "orchestrator_router";

    private PromptKeys() {}
}
