package com.diagnostic.agent.agent;

import com.diagnostic.agent.tool.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosisReportTest {

    private static DiagnosisResult successResult(String name, RiskLevel risk) {
        return DiagnosisResult.success(name, name + "检查正常", "detail", risk, 100);
    }

    private static DiagnosisResult failedResult(String name) {
        return DiagnosisResult.failure(name, name + "执行失败");
    }

    // ---- aggregate ----

    @Test
    void shouldAggregateSingleResult() {
        DiagnosisResult r = successResult("SqlAgent", RiskLevel.HIGH);
        DiagnosisReport report = DiagnosisReport.aggregate("s1", List.of(r));

        assertThat(report.success()).isTrue();
        assertThat(report.overallRisk()).isEqualTo(RiskLevel.HIGH);
        assertThat(report.agentResults()).hasSize(1);
        assertThat(report.finalSummary()).contains("SqlAgent");
    }

    @Test
    void shouldAggregateMultipleResults() {
        List<DiagnosisResult> results = List.of(
                successResult("SqlAgent", RiskLevel.LOW),
                successResult("CpuAgent", RiskLevel.HIGH)
        );
        DiagnosisReport report = DiagnosisReport.aggregate("s2", results);

        assertThat(report.success()).isTrue();
        assertThat(report.overallRisk()).isEqualTo(RiskLevel.HIGH);
        assertThat(report.agentResults()).hasSize(2);
        assertThat(report.finalSummary()).contains("SqlAgent").contains("CpuAgent");
    }

    @Test
    void shouldTakeOverallRiskAsHighest() {
        List<DiagnosisResult> results = List.of(
                successResult("a", RiskLevel.LOW),
                successResult("b", RiskLevel.MEDIUM),
                successResult("c", RiskLevel.HIGH)
        );
        assertThat(DiagnosisReport.aggregate("s", results).overallRisk()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void shouldSkipFailedResultsInRiskAggregation() {
        List<DiagnosisResult> results = List.of(
                failedResult("a"),
                successResult("b", RiskLevel.MEDIUM)
        );
        DiagnosisReport report = DiagnosisReport.aggregate("s", results);

        assertThat(report.success()).isTrue();
        assertThat(report.overallRisk()).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void shouldMarkUnknownWhenAllFailed() {
        List<DiagnosisResult> results = List.of(
                failedResult("a"),
                failedResult("b")
        );
        DiagnosisReport report = DiagnosisReport.aggregate("s", results);

        assertThat(report.success()).isFalse();
        assertThat(report.overallRisk()).isEqualTo(RiskLevel.UNKNOWN);
    }

    @Test
    void shouldHandleEmptyResults() {
        DiagnosisReport report = DiagnosisReport.aggregate("s", Collections.emptyList());

        assertThat(report.success()).isFalse();
        assertThat(report.overallRisk()).isEqualTo(RiskLevel.UNKNOWN);
        assertThat(report.agentResults()).isEmpty();
    }

    // ---- fromSingle ----

    @Test
    void shouldWrapSingleSuccessResult() {
        DiagnosisResult r = successResult("GeneralAgent", RiskLevel.LOW);
        DiagnosisReport report = DiagnosisReport.fromSingle("s", r);

        assertThat(report.success()).isTrue();
        assertThat(report.agentResults()).hasSize(1);
        assertThat(report.overallRisk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void shouldWrapSingleFailedResult() {
        DiagnosisResult r = failedResult("GeneralAgent");
        DiagnosisReport report = DiagnosisReport.fromSingle("s", r);

        assertThat(report.success()).isFalse();
        assertThat(report.overallRisk()).isEqualTo(RiskLevel.UNKNOWN);
    }
}
