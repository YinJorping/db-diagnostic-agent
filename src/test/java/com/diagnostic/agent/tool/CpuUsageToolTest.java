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
class CpuUsageToolTest {

    @Mock private CpuMetricsProvider provider;

    private CpuUsageTool tool;

    @BeforeEach
    void setup() {
        tool = new CpuUsageTool(provider, new CpuProperties());
    }

    // ---- 工具基本属性 ----

    @Test
    void shouldReturnCorrectToolName() {
        assertThat(tool.getName()).isEqualTo("CpuUsageTool");
    }

    @Test
    void shouldReturnNonEmptyDescription() {
        assertThat(tool.getDescription()).isNotBlank();
    }

    // ---- 正常场景 ----

    @Test
    void shouldReturnLowRiskWhenCpuIsNormal() {
        when(provider.sample()).thenReturn(new CpuMetrics(0.3, 0.2, 2.0, 8));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.LOW);
        assertThat(result.getSummary()).contains("LOW");
    }

    // ---- R1: systemCpuLoad > 0.9 → HIGH ----

    @Test
    void shouldDetectHighSystemCpuLoad() {
        when(provider.sample()).thenReturn(new CpuMetrics(0.95, 0.3, -1, 8));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getSummary()).contains("HIGH");
    }

    // ---- R2: systemCpuLoad > 0.7 → MEDIUM ----

    @Test
    void shouldDetectMediumSystemCpuLoad() {
        when(provider.sample()).thenReturn(new CpuMetrics(0.75, 0.3, -1, 8));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.MEDIUM);
    }

    // ---- R3: processCpuLoad > 0.8 → HIGH ----

    @Test
    void shouldDetectHighProcessCpuLoad() {
        when(provider.sample()).thenReturn(new CpuMetrics(0.3, 0.85, -1, 8));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.HIGH);
    }

    // ---- R4: loadAverage > cores * 1.5 → HIGH ----

    @Test
    void shouldDetectHighLoadAverage() {
        when(provider.sample()).thenReturn(new CpuMetrics(0.3, 0.2, 16.0, 8));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.HIGH);
    }

    // ---- R5: loadAverage > cores → MEDIUM ----

    @Test
    void shouldDetectMediumLoadAverage() {
        when(provider.sample()).thenReturn(new CpuMetrics(0.3, 0.2, 10.0, 8));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.MEDIUM);
    }

    // ---- 多规则同时命中 → HIGH 覆盖 MEDIUM ----

    @Test
    void shouldTakeHighestRiskWhenMultipleRulesMatch() {
        when(provider.sample()).thenReturn(new CpuMetrics(0.95, 0.85, 16.0, 8));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.HIGH);
    }

    // ---- 指标不可用 ----

    @Test
    void shouldSkipSystemCpuRulesWhenUnavailable() {
        when(provider.sample()).thenReturn(new CpuMetrics(-1, -1, 2.0, 8));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.LOW);
    }

    @Test
    void shouldSkipLoadAverageRulesWhenUnavailable() {
        when(provider.sample()).thenReturn(new CpuMetrics(0.5, 0.3, -1, 8));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.LOW);
    }

    // ---- detail 结构验证 ----

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeMetricsInDetail() {
        when(provider.sample()).thenReturn(new CpuMetrics(0.5, 0.3, 2.0, 8));

        ToolResult result = tool.execute(Map.of());

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        assertThat(detail).containsKeys("risk", "findings", "suggestions", "metrics");

        Map<String, Object> metrics = (Map<String, Object>) detail.get("metrics");
        assertThat(metrics).containsKeys(
                "systemCpuLoad", "processCpuLoad", "systemLoadAverage", "availableProcessors");
    }

    // ---- Provider 异常 ----

    @Test
    void shouldReturnFailureOnProviderException() {
        when(provider.sample()).thenThrow(new RuntimeException("JMX 不可用"));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("JMX 不可用");
    }

    // ---- 阈值使用 CpuProperties 默认值 ----

    @Test
    void shouldUseCustomThresholdsFromProperties() {
        CpuProperties customProps = new CpuProperties();
        customProps.setThresholdSystemHigh(0.5); // 降低阈值
        CpuUsageTool customTool = new CpuUsageTool(provider, customProps);

        when(provider.sample()).thenReturn(new CpuMetrics(0.6, 0.2, -1, 8));

        ToolResult result = customTool.execute(Map.of());

        assertThat(result.getRisk()).isEqualTo(RiskLevel.HIGH); // 0.6 > 0.5
    }
}
