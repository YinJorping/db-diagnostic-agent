package com.diagnostic.agent.eval;

import com.diagnostic.agent.tool.RiskLevel;

public record EvalResult(
        String caseId,
        String caseDescription,
        String actualAgentName,
        RiskLevel actualRisk,
        String actualResponseSummary,
        long latencyMs,
        int promptTokens,
        int completionTokens,
        boolean agentMatch,
        boolean riskMatch,
        double keywordCoverage,
        double recommendationCoverage,
        boolean diagnosisSuccess,
        String errorMessage) {
}
