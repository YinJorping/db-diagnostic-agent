package com.diagnostic.agent.eval;

import com.diagnostic.agent.agent.DiagnosisResult;
import com.diagnostic.agent.tool.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EvalScorerTest {

    private final EvalScorer scorer = new EvalScorer();

    @Test
    void shouldScorePerfectMatch() {
        EvalCase evalCase = new EvalCase("sql-001", "全表扫描",
                "SELECT * FROM t",
                new EvalCase.ExpectedCriteria("SqlDiagnosisAgent", RiskLevel.HIGH,
                        List.of("全表扫描", "索引"), 1,
                        List.of("创建索引", "EXPLAIN分析")));

        DiagnosisResult result = DiagnosisResult.success("SqlDiagnosisAgent",
                "检测到全表扫描，建议对问题字段创建索引，使用EXPLAIN分析",
                "detail", RiskLevel.HIGH, 1000L, 100, 50);

        EvalResult er = scorer.evaluate(evalCase, result, 100, 50, 2000L);

        assertThat(er.agentMatch()).isTrue();
        assertThat(er.riskMatch()).isTrue();
        assertThat(er.keywordCoverage()).isEqualTo(1.0);
        assertThat(er.recommendationCoverage()).isEqualTo(1.0);
    }

    @Test
    void shouldDetectMismatchedAgentAndRisk() {
        EvalCase evalCase = new EvalCase("cpu-001", "CPU高",
                "CPU 100%",
                new EvalCase.ExpectedCriteria("CpuDiagnosisAgent", RiskLevel.HIGH,
                        List.of("CPU"), 1, List.of()));

        DiagnosisResult result = DiagnosisResult.success("SqlDiagnosisAgent",
                "summary", "detail", RiskLevel.LOW, 500L, 80, 40);

        EvalResult er = scorer.evaluate(evalCase, result, 100, 50, 1000L);

        assertThat(er.agentMatch()).isFalse();
        assertThat(er.riskMatch()).isFalse();
    }

    @Test
    void shouldComputePartialKeywordCoverage() {
        EvalCase evalCase = new EvalCase("sql-001", "test",
                "problem",
                new EvalCase.ExpectedCriteria("SqlDiagnosisAgent", RiskLevel.MEDIUM,
                        List.of("全表扫描", "索引", "慢查询", "filesort"), 2,
                        List.of()));

        DiagnosisResult result = DiagnosisResult.success("SqlDiagnosisAgent",
                "检测到全表扫描和索引问题", "detail", RiskLevel.MEDIUM, 500L, 100, 50);

        EvalResult er = scorer.evaluate(evalCase, result, 100, 50, 1000L);

        assertThat(er.keywordCoverage()).isEqualTo(0.5); // 2 of 4 matched
    }

    @Test
    void shouldComputePartialRecommendationCoverage() {
        EvalCase evalCase = new EvalCase("sql-001", "test",
                "problem",
                new EvalCase.ExpectedCriteria("SqlDiagnosisAgent", RiskLevel.LOW,
                        List.of(), 0,
                        List.of("创建索引", "增加缓存", "SQL改写")));

        DiagnosisResult result = DiagnosisResult.success("SqlDiagnosisAgent",
                "建议创建索引，进行SQL改写", "detail", RiskLevel.LOW, 500L, 90, 45);

        EvalResult er = scorer.evaluate(evalCase, result, 100, 50, 1000L);
        assertThat(er.recommendationCoverage()).isEqualTo(2.0 / 3.0);
    }

    @Test
    void shouldHandleCaseInsensitiveKeywordMatching() {
        EvalCase evalCase = new EvalCase("sql-001", "test",
                "problem",
                new EvalCase.ExpectedCriteria("SqlDiagnosisAgent", RiskLevel.LOW,
                        List.of("CPU", "Load"), 1, List.of()));

        DiagnosisResult result = DiagnosisResult.success("SqlDiagnosisAgent",
                "cpu load is high", "detail", RiskLevel.LOW, 500L, 70, 30);

        EvalResult er = scorer.evaluate(evalCase, result, 100, 50, 1000L);
        assertThat(er.keywordCoverage()).isEqualTo(1.0);
    }

    @Test
    void shouldReturnFullScoreForEmptyExpectedLists() {
        EvalCase evalCase = new EvalCase("sql-001", "test",
                "problem",
                new EvalCase.ExpectedCriteria("SqlDiagnosisAgent", RiskLevel.LOW,
                        List.of(), 0, List.of()));

        DiagnosisResult result = DiagnosisResult.success("SqlDiagnosisAgent",
                "some response", "detail", RiskLevel.LOW, 500L, 60, 30);

        EvalResult er = scorer.evaluate(evalCase, result, 100, 50, 1000L);
        assertThat(er.keywordCoverage()).isEqualTo(1.0);
        assertThat(er.recommendationCoverage()).isEqualTo(1.0);
    }

    @Test
    void shouldComputeMetricsFromResults() {
        EvalResult r1 = new EvalResult("c1", "d1", "A", RiskLevel.HIGH, "resp",
                1000, 100, 50, true, true, 0.8, 1.0, true, null);
        EvalResult r2 = new EvalResult("c2", "d2", "B", RiskLevel.LOW, "resp",
                2000, 80, 40, false, false, 0.5, 0.0, true, null);

        EvalReport.EvalMetrics metrics = scorer.computeMetrics(List.of(r1, r2));

        assertThat(metrics.agentAccuracy()).isEqualTo(0.5);
        assertThat(metrics.riskAccuracy()).isEqualTo(0.5);
        assertThat(metrics.keywordCoverage()).isEqualTo(0.65);
        assertThat(metrics.recommendationCoverage()).isEqualTo(0.5);
        assertThat(metrics.totalCases()).isEqualTo(2);
    }

    @Test
    void shouldHandleEmptyResultsForMetrics() {
        EvalReport.EvalMetrics metrics = scorer.computeMetrics(List.of());
        assertThat(metrics.totalCases()).isEqualTo(0);
    }

    @Test
    void shouldHandleFailureDiagnosisResult() {
        EvalCase evalCase = new EvalCase("sql-001", "test",
                "problem",
                new EvalCase.ExpectedCriteria("SqlDiagnosisAgent", RiskLevel.HIGH,
                        List.of("索引"), 1, List.of()));

        DiagnosisResult result = DiagnosisResult.failure("SqlDiagnosisAgent", "连接超时");

        EvalResult er = scorer.evaluate(evalCase, result, 0, 0, 100L);
        assertThat(er.diagnosisSuccess()).isFalse();
        assertThat(er.errorMessage()).isEqualTo("连接超时");
        assertThat(er.keywordCoverage()).isEqualTo(0.0);
    }
}
