package com.diagnostic.agent.agent;

import com.diagnostic.agent.tool.RiskLevel;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

public record DiagnosisReport(
        String sessionId,
        List<AgentResult> agentResults,
        RiskLevel overallRisk,
        String finalSummary,
        boolean success,
        Instant timestamp) {

    /**
     * Lightweight per-agent result entry in the report.
     */
    public record AgentResult(String agentName, boolean success, String summary, RiskLevel risk) {
        public static AgentResult from(DiagnosisResult result) {
            return new AgentResult(result.getAgentName(), result.isSuccess(), result.getSummary(), result.getRisk());
        }
    }

    // ---- factories ----

    public static DiagnosisReport aggregate(String sessionId, List<DiagnosisResult> results) {
        List<AgentResult> entries = results.stream().map(AgentResult::from).toList();
        boolean anySuccess = entries.stream().anyMatch(AgentResult::success);
        RiskLevel risk = computeOverallRisk(entries);
        // TODO Day9: Replace with LLM-based aggregation summary
        String summary = buildSummary(entries);
        return new DiagnosisReport(sessionId, entries, risk, summary, anySuccess, Instant.now());
    }

    public static DiagnosisReport fromSingle(String sessionId, DiagnosisResult result) {
        AgentResult entry = AgentResult.from(result);
        return new DiagnosisReport(
                sessionId,
                List.of(entry),
                result.isSuccess() ? result.getRisk() : RiskLevel.UNKNOWN,
                result.getSummary(),
                result.isSuccess(),
                Instant.now()
        );
    }

    // ---- helpers ----

    /**
     * 聚合风险：取所有成功结果中的最高等级。无成功结果返回 UNKNOWN。
     */
    private static RiskLevel computeOverallRisk(List<AgentResult> entries) {
        List<RiskLevel> risks = entries.stream()
                .filter(AgentResult::success)
                .map(AgentResult::risk)
                .toList();
        if (risks.isEmpty()) return RiskLevel.UNKNOWN;
        if (risks.contains(RiskLevel.HIGH)) return RiskLevel.HIGH;
        if (risks.contains(RiskLevel.MEDIUM)) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    private static String buildSummary(List<AgentResult> entries) {
        return entries.stream()
                .map(e -> "【" + e.agentName() + "】" + e.summary())
                .collect(Collectors.joining("\n"));
    }
}
