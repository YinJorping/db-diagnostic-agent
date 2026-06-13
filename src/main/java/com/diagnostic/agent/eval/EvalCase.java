package com.diagnostic.agent.eval;

import com.diagnostic.agent.tool.RiskLevel;

import java.util.List;

public record EvalCase(
        String id,
        String description,
        String problem,
        ExpectedCriteria expected) {

    public record ExpectedCriteria(
            String agent,
            RiskLevel risk,
            List<String> keywords,
            int minKeywordMatches,
            List<String> recommendations) {
    }
}
