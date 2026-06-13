package com.diagnostic.agent.eval;

import java.time.Instant;
import java.util.List;

public record EvalReport(
        String runId,
        String domain,
        String promptVariantDescription,
        Instant startTime,
        Instant endTime,
        EvalRunStatus status,
        List<EvalResult> results,
        EvalMetrics metrics) {

    public record EvalMetrics(
            double agentAccuracy,
            double riskAccuracy,
            double keywordCoverage,
            double recommendationCoverage,
            double avgLatencyMs,
            double avgPromptTokens,
            double avgCompletionTokens,
            int totalCases,
            int passedCases,
            int failedCases) {
    }
}
