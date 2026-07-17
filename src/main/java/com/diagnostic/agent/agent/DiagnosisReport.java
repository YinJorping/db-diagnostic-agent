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
        Instant timestamp,
        int summarizerPromptTokens,
        int summarizerCompletionTokens) {

    public record AgentResult(String agentName, boolean success, String summary, RiskLevel risk,
                              int promptTokens, int completionTokens) {
        public static AgentResult from(DiagnosisResult result) {
            return new AgentResult(result.getAgentName(), result.isSuccess(), result.getSummary(), result.getRisk(),
                    result.getPromptTokens(), result.getCompletionTokens());
        }
    }

    // ---- factories ----

    /** 多 Agent 聚合（LLM 总结由调用方传入）。 */
    public static DiagnosisReport aggregate(String sessionId, List<DiagnosisResult> results,
                                            String finalSummary, int summarizerPromptTokens, int summarizerCompletionTokens) {
        List<AgentResult> entries = results.stream().map(AgentResult::from).toList();
        boolean anySuccess = entries.stream().anyMatch(AgentResult::success);
        RiskLevel risk = computeOverallRisk(entries);
        return new DiagnosisReport(sessionId, entries, risk, finalSummary, anySuccess, Instant.now(),
                summarizerPromptTokens, summarizerCompletionTokens);
    }

    /** 多 Agent 聚合（简单拼接摘要，用于测试或不需要 LLM 的场景）。 */
    public static DiagnosisReport aggregate(String sessionId, List<DiagnosisResult> results) {
        String summary = results.stream()
                .map(r -> "【" + r.getAgentName() + "】" + r.getSummary())
                .collect(Collectors.joining("\n"));
        return aggregate(sessionId, results, summary, 0, 0);
    }

    public static DiagnosisReport fromSingle(String sessionId, DiagnosisResult result) {
        AgentResult entry = AgentResult.from(result);
        return new DiagnosisReport(
                sessionId,
                List.of(entry),
                result.isSuccess() ? result.getRisk() : RiskLevel.UNKNOWN,
                result.getSummary(),
                result.isSuccess(),
                Instant.now(),
                0, 0
        );
    }

    // ---- helpers ----

    private static RiskLevel computeOverallRisk(List<AgentResult> entries) {
        List<RiskLevel> risks = entries.stream()
                .filter(AgentResult::success)
                .map(AgentResult::risk)
                .toList();
        if (risks.isEmpty()) return RiskLevel.UNKNOWN;
        if (risks.contains(RiskLevel.HIGH)) return RiskLevel.HIGH;
        if (risks.contains(RiskLevel.MEDIUM)) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }}
