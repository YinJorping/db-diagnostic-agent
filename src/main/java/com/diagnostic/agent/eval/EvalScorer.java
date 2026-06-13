package com.diagnostic.agent.eval;

import com.diagnostic.agent.agent.DiagnosisResult;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class EvalScorer {

    public EvalResult evaluate(EvalCase evalCase, DiagnosisResult diagResult,
                                int promptTokens, int completionTokens, long latencyMs) {
        EvalCase.ExpectedCriteria expected = evalCase.expected();

        boolean agentMatch = expected.agent().equals(diagResult.getAgentName());

        boolean riskMatch = expected.risk() == diagResult.getRisk();

        String responseLower = (diagResult.getSummary() != null ? diagResult.getSummary().toLowerCase() : "")
                + " " + (diagResult.getDetail() != null ? diagResult.getDetail().toLowerCase() : "");

        long matchedKws = expected.keywords().stream()
                .filter(kw -> responseLower.contains(kw.toLowerCase()))
                .count();
        double keywordCoverage = expected.keywords().isEmpty()
                ? 1.0 : (double) matchedKws / expected.keywords().size();

        long matchedRecs = expected.recommendations().stream()
                .filter(rec -> responseLower.contains(rec.toLowerCase()))
                .count();
        double recommendationCoverage = expected.recommendations().isEmpty()
                ? 1.0 : (double) matchedRecs / expected.recommendations().size();

        return new EvalResult(
                evalCase.id(),
                evalCase.description(),
                diagResult.getAgentName(),
                diagResult.getRisk(),
                diagResult.getSummary(),
                latencyMs,
                promptTokens,
                completionTokens,
                agentMatch,
                riskMatch,
                keywordCoverage,
                recommendationCoverage,
                diagResult.isSuccess(),
                diagResult.getError()
        );
    }

    public EvalReport.EvalMetrics computeMetrics(List<EvalResult> results) {
        int total = results.size();
        if (total == 0) {
            return new EvalReport.EvalMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        long agentHits = results.stream().filter(EvalResult::agentMatch).count();
        long riskHits = results.stream().filter(EvalResult::riskMatch).count();
        double avgKwCoverage = results.stream().mapToDouble(EvalResult::keywordCoverage).average().orElse(0);
        double avgRecCoverage = results.stream().mapToDouble(EvalResult::recommendationCoverage).average().orElse(0);
        double avgLatency = results.stream().mapToLong(EvalResult::latencyMs).average().orElse(0);
        double avgPromptTokens = results.stream().mapToInt(EvalResult::promptTokens).average().orElse(0);
        double avgCompletionTokens = results.stream().mapToInt(EvalResult::completionTokens).average().orElse(0);

        long passed = results.stream()
                .filter(r -> r.keywordCoverage() >= 0.5)
                .count();

        return new EvalReport.EvalMetrics(
                (double) agentHits / total,
                (double) riskHits / total,
                avgKwCoverage,
                avgRecCoverage,
                avgLatency,
                avgPromptTokens,
                avgCompletionTokens,
                total,
                (int) passed,
                total - (int) passed
        );
    }
}
