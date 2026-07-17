package com.diagnostic.agent.controller.dto;

import com.diagnostic.agent.agent.DiagnosisReport;
import com.diagnostic.agent.tool.RiskLevel;

import java.util.stream.Collectors;

public record DiagnosisResponse(
        String sessionId,
        String agentName,
        String summary,
        RiskLevel risk,
        int agentCount,
        int totalPromptTokens,
        int totalCompletionTokens) {

    public static DiagnosisResponse from(DiagnosisReport report, String sessionId) {
        int agentPrompt = report.agentResults().stream().mapToInt(DiagnosisReport.AgentResult::promptTokens).sum();
        int agentCompletion = report.agentResults().stream().mapToInt(DiagnosisReport.AgentResult::completionTokens).sum();
        return new DiagnosisResponse(
                sessionId,
                report.agentResults().stream()
                        .map(DiagnosisReport.AgentResult::agentName)
                        .collect(Collectors.joining(", ")),
                report.finalSummary(),
                report.overallRisk(),
                report.agentResults().size(),
                agentPrompt + report.summarizerPromptTokens(),
                agentCompletion + report.summarizerCompletionTokens());
    }
}
