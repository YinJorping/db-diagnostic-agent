package com.diagnostic.agent.controller.dto;

import com.diagnostic.agent.agent.DiagnosisReport;
import com.diagnostic.agent.tool.RiskLevel;

import java.util.stream.Collectors;

public record DiagnosisResponse(
        String sessionId,
        String agentName,
        String summary,
        RiskLevel risk,
        int agentCount) {

    public static DiagnosisResponse from(DiagnosisReport report, String sessionId) {
        return new DiagnosisResponse(
                sessionId,
                report.agentResults().stream()
                        .map(DiagnosisReport.AgentResult::agentName)
                        .collect(Collectors.joining(", ")),
                report.finalSummary(),
                report.overallRisk(),
                report.agentResults().size());
    }
}
