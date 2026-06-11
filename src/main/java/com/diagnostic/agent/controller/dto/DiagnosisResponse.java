package com.diagnostic.agent.controller.dto;

import com.diagnostic.agent.agent.DiagnosisResult;
import com.diagnostic.agent.tool.RiskLevel;

public record DiagnosisResponse(
        String sessionId,
        String agentName,
        String summary,
        RiskLevel risk) {

    public static DiagnosisResponse from(DiagnosisResult result, String sessionId) {
        return new DiagnosisResponse(
                sessionId,
                result.getAgentName(),
                result.getSummary(),
                result.getRisk());
    }
}
