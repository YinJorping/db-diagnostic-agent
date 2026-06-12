package com.diagnostic.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JvmUsageToolTest {

    @Mock private JvmMetricsProvider provider;

    private JvmUsageTool tool;

    @BeforeEach
    void setup() {
        tool = new JvmUsageTool(provider, new JvmProperties());
    }

    // ---- 工具基本属性 ----

    @Test
    void shouldReturnCorrectToolName() {
        assertThat(tool.getName()).isEqualTo("JvmUsageTool");
    }

    @Test
    void shouldReturnNonEmptyDescription() {
        assertThat(tool.getDescription()).isNotBlank();
    }

    // ---- 正常场景 ----

    @Test
    void shouldReturnLowRiskWhenJvmIsHealthy() {
        when(provider.sample()).thenReturn(jvmMetrics(500 * MB, 1024 * MB, 100 * MB, 200 * MB, 50, 100, 60_000));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.LOW);
        assertThat(result.getSummary()).contains("LOW");
    }

    // ---- R1: heapUsageRatio > 0.85 → HIGH ----

    @Test
    void shouldDetectHighHeapUsage() {
        when(provider.sample()).thenReturn(jvmMetrics(950 * MB, 1024 * MB, 100 * MB, 200 * MB, 50, 100, 60_000));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getSummary()).contains("HIGH");
    }

    // ---- R2: heapUsageRatio > 0.70 → MEDIUM ----

    @Test
    void shouldDetectMediumHeapUsage() {
        when(provider.sample()).thenReturn(jvmMetrics(800 * MB, 1024 * MB, 100 * MB, 200 * MB, 50, 100, 60_000));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.MEDIUM);
    }

    // ---- R3: nonHeapUsageRatio > 0.90 → HIGH ----

    @Test
    void shouldDetectHighNonHeapUsage() {
        when(provider.sample()).thenReturn(jvmMetrics(500 * MB, 1024 * MB, 190 * MB, 200 * MB, 50, 100, 60_000));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.HIGH);
    }

    // ---- R4: threadCount > 500 → MEDIUM ----

    @Test
    void shouldDetectHighThreadCount() {
        when(provider.sample()).thenReturn(jvmMetrics(500 * MB, 1024 * MB, 100 * MB, 200 * MB, 600, 1000, 60_000));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.MEDIUM);
    }

    // ---- heapMax=-1 (Xmx 未设置) → 跳过 R1/R2 ----

    @Test
    void shouldSkipHeapRulesWhenXmxNotSet() {
        when(provider.sample()).thenReturn(jvmMetrics(800 * MB, -1, 100 * MB, 200 * MB, 50, 100, 60_000));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.LOW);
    }

    // ---- heapMax=0 → 跳过 R1/R2 ----

    @Test
    void shouldSkipHeapRulesWhenHeapMaxIsZero() {
        when(provider.sample()).thenReturn(jvmMetrics(100 * MB, 0, 100 * MB, 200 * MB, 50, 100, 60_000));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.LOW);
    }

    // ---- nonHeapCommitted=0 → 跳过 R3 ----

    @Test
    void shouldSkipNonHeapRuleWhenCommittedIsZero() {
        when(provider.sample()).thenReturn(jvmMetrics(500 * MB, 1024 * MB, 100 * MB, 0, 50, 100, 60_000));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.LOW);
    }

    // ---- 多规则同时命中 → HIGH 覆盖 MEDIUM ----

    @Test
    void shouldTakeHighestRiskWhenMultipleRulesMatch() {
        when(provider.sample()).thenReturn(jvmMetrics(950 * MB, 1024 * MB, 190 * MB, 200 * MB, 600, 1000, 60_000));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.HIGH);
    }

    // ---- detail 结构验证 ----

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeMetricsInDetail() {
        when(provider.sample()).thenReturn(jvmMetrics(500 * MB, 1024 * MB, 100 * MB, 200 * MB, 50, 100, 60_000));

        ToolResult result = tool.execute(Map.of());

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        assertThat(detail).containsKeys("risk", "findings", "suggestions", "metrics", "gcSnapshots");

        Map<String, Object> metrics = (Map<String, Object>) detail.get("metrics");
        assertThat(metrics).containsKeys(
                "heapUsedMB", "heapMaxMB", "heapMaxSet",
                "nonHeapUsedMB", "nonHeapCommittedMB",
                "threadCount", "peakThreadCount", "daemonThreadCount", "uptimeMinutes");
    }

    // ---- GC 数据不产生 finding ----

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeGcDataButNotAsFinding() {
        List<GcSnapshot> gcSnapshots = List.of(
                new GcSnapshot("G1 Young Generation", 100, 5000),
                new GcSnapshot("G1 Old Generation", 5, 2000));
        JvmMetrics metrics = new JvmMetrics(500 * MB, 1024 * MB, 100 * MB, 200 * MB,
                gcSnapshots, 50, 100, 50, 60_000);
        when(provider.sample()).thenReturn(metrics);

        ToolResult result = tool.execute(Map.of());

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        List<Map<String, String>> findings = (List<Map<String, String>>) detail.get("findings");
        assertThat(findings).noneMatch(f -> f.get("nodeType").equals("GcOverhead"));
        List<Map<String, Object>> gcList = (List<Map<String, Object>>) detail.get("gcSnapshots");
        assertThat(gcList).hasSize(2);
        assertThat(gcList.get(0).get("name")).isEqualTo("G1 Young Generation");
    }

    // ---- Provider 异常 ----

    @Test
    void shouldReturnFailureOnProviderException() {
        when(provider.sample()).thenThrow(new RuntimeException("JMX 不可用"));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("JMX 不可用");
    }

    // ---- 自定义阈值 ----

    @Test
    void shouldUseCustomThresholds() {
        JvmProperties customProps = new JvmProperties();
        customProps.setThresholdHeapHigh(0.50);
        JvmUsageTool customTool = new JvmUsageTool(provider, customProps);

        when(provider.sample()).thenReturn(jvmMetrics(600 * MB, 1024 * MB, 100 * MB, 200 * MB, 50, 100, 60_000));

        ToolResult result = customTool.execute(Map.of());

        assertThat(result.getRisk()).isEqualTo(RiskLevel.HIGH);
    }

    // ---- helper ----

    private static final long MB = 1024 * 1024;

    private static JvmMetrics jvmMetrics(long heapUsed, long heapMax, long nonHeapUsed,
                                          long nonHeapCommitted, int threadCount,
                                          int peakThread, long uptimeMs) {
        return new JvmMetrics(heapUsed, heapMax, nonHeapUsed, nonHeapCommitted,
                List.of(new GcSnapshot("G1 Young Generation", 100, 5000)),
                threadCount, peakThread, threadCount / 2, uptimeMs);
    }
}
