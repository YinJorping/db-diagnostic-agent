package com.diagnostic.agent.agent;

import com.diagnostic.agent.tool.RiskLevel;
import lombok.Getter;

import java.time.Instant;

/**
 * Agent 诊断结果值对象。
 * 风格与 {@link com.diagnostic.agent.tool.ToolResult} 保持一致。
 */
@Getter
public class DiagnosisResult {

    private final boolean success;
    private final String agentName;
    private final String summary;
    private final String detail;
    private final RiskLevel risk;
    private final long executionTimeMs;
    private final String error;
    private final Instant timestamp;
    private final int promptTokens;
    private final int completionTokens;

    private DiagnosisResult(boolean success, String agentName, String summary,
                            String detail, RiskLevel risk, long executionTimeMs, String error,
                            int promptTokens, int completionTokens) {
        this.success = success;
        this.agentName = agentName;
        this.summary = summary;
        this.detail = detail;
        this.risk = risk;
        this.executionTimeMs = executionTimeMs;
        this.error = error;
        this.timestamp = Instant.now();
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    // ---- 成功工厂 ----

    public static DiagnosisResult success(String agentName, String summary,
                                          String detail, RiskLevel risk, long executionTimeMs,
                                          int promptTokens, int completionTokens) {
        return new DiagnosisResult(true, agentName, summary, detail, risk, executionTimeMs, null,
                promptTokens, completionTokens);
    }

    // ---- 失败工厂 ----

    public static DiagnosisResult failure(String agentName, String error) {
        return new DiagnosisResult(false, agentName, null, null, RiskLevel.UNKNOWN, 0, error, 0, 0);
    }
}
