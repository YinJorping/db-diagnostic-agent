package com.diagnostic.agent.tool;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticUtilsTest {

    // ---- finding ----

    @Test
    void findingShouldReturnMapWithThreeKeys() {
        Map<String, String> f = DiagnosticUtils.finding("HIGH", "DiskUsage", "磁盘使用率过高");

        assertThat(f).containsKeys("level", "nodeType", "description");
        assertThat(f.get("level")).isEqualTo("HIGH");
        assertThat(f.get("nodeType")).isEqualTo("DiskUsage");
        assertThat(f.get("description")).isEqualTo("磁盘使用率过高");
    }

    // ---- suggestion ----

    @Test
    void suggestionShouldReturnMapWithThreeKeys() {
        Map<String, String> s = DiagnosticUtils.suggestion("HIGH", "扩容磁盘", "磁盘空间不足 10GB");

        assertThat(s).containsKeys("priority", "action", "reason");
        assertThat(s.get("priority")).isEqualTo("HIGH");
        assertThat(s.get("action")).isEqualTo("扩容磁盘");
        assertThat(s.get("reason")).isEqualTo("磁盘空间不足 10GB");
    }

    // ---- determineRisk ----

    @Test
    void determineRiskShouldReturnHighWhenHasHigh() {
        List<Map<String, String>> findings = List.of(
                DiagnosticUtils.finding("HIGH", "A", "a"),
                DiagnosticUtils.finding("LOW", "B", "b"));

        assertThat(DiagnosticUtils.determineRisk(findings)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void determineRiskShouldReturnMediumWhenHasMediumNoHigh() {
        List<Map<String, String>> findings = List.of(
                DiagnosticUtils.finding("MEDIUM", "A", "a"),
                DiagnosticUtils.finding("LOW", "B", "b"));

        assertThat(DiagnosticUtils.determineRisk(findings)).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    void determineRiskShouldReturnLowWhenEmpty() {
        assertThat(DiagnosticUtils.determineRisk(List.of())).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void determineRiskShouldReturnLowWhenAllLow() {
        List<Map<String, String>> findings = List.of(
                DiagnosticUtils.finding("LOW", "A", "a"));

        assertThat(DiagnosticUtils.determineRisk(findings)).isEqualTo(RiskLevel.LOW);
    }

    // ---- dedupByAction ----

    @Test
    void dedupByActionShouldKeepFirstByAction() {
        List<Map<String, String>> suggestions = List.of(
                DiagnosticUtils.suggestion("HIGH", "扩容磁盘", "first reason"),
                DiagnosticUtils.suggestion("MEDIUM", "扩容磁盘", "second reason"));

        List<Map<String, String>> result = DiagnosticUtils.dedupByAction(suggestions);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).get("reason")).isEqualTo("first reason");
    }

    @Test
    void dedupByActionShouldPreserveOrder() {
        List<Map<String, String>> suggestions = List.of(
                DiagnosticUtils.suggestion("HIGH", "action-a", "a"),
                DiagnosticUtils.suggestion("HIGH", "action-b", "b"),
                DiagnosticUtils.suggestion("MEDIUM", "action-c", "c"));

        List<Map<String, String>> result = DiagnosticUtils.dedupByAction(suggestions);

        assertThat(result).hasSize(3);
        assertThat(result.get(0).get("action")).isEqualTo("action-a");
        assertThat(result.get(1).get("action")).isEqualTo("action-b");
        assertThat(result.get(2).get("action")).isEqualTo("action-c");
    }

    @Test
    void dedupByActionShouldReturnEmptyListForEmpty() {
        assertThat(DiagnosticUtils.dedupByAction(List.of())).isEmpty();
    }

    @Test
    void dedupByActionShouldHandleNullAction() {
        Map<String, String> entry = DiagnosticUtils.finding("HIGH", "DiskUsage", "desc");
        entry.put("action", null);

        List<Map<String, String>> result = DiagnosticUtils.dedupByAction(List.of(entry));
        assertThat(result).hasSize(1);
    }

    // ---- utility class protection ----

    @Test
    void constructorShouldBePrivate() throws Exception {
        Constructor<DiagnosticUtils> ctor = DiagnosticUtils.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        try {
            ctor.newInstance();
        } catch (InvocationTargetException e) {
            assertThat(e.getCause()).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    // ---- formatPercent ----
    // NOTE: 验证 FormatUtil.formatPercent 逻辑与迁移前各 Tool 的 private formatPercent 一致

    @Test
    void formatPercentShouldReturnZeroForZero() {
        assertThat(com.diagnostic.agent.common.util.FormatUtil.formatPercent(0.0)).isEqualTo("0%");
    }

    @Test
    void formatPercentShouldRoundCorrectly() {
        assertThat(com.diagnostic.agent.common.util.FormatUtil.formatPercent(0.855)).isEqualTo("86%");
    }

    @Test
    void formatPercentShouldReturn100ForOne() {
        assertThat(com.diagnostic.agent.common.util.FormatUtil.formatPercent(1.0)).isEqualTo("100%");
    }
}
