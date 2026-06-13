package com.diagnostic.agent.tool;

import com.diagnostic.agent.common.util.FormatUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class CpuUsageTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CpuUsageTool.class);

    private final CpuMetricsProvider provider;
    private final CpuProperties cpuProps;

    public CpuUsageTool(CpuMetricsProvider provider, CpuProperties cpuProps) {
        this.provider = provider;
        this.cpuProps = cpuProps;
    }

    @Override
    public String getName() {
        return "CpuUsageTool";
    }

    @Override
    public String getDescription() {
        return "采集系统CPU使用率和负载，评估CPU资源健康度";
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        long start = System.currentTimeMillis();
        try {
            CpuMetrics metrics = provider.sample();
            Map<String, Object> detail = analyze(metrics);
            RiskLevel risk = RiskLevel.valueOf((String) detail.get("risk"));
            long elapsed = System.currentTimeMillis() - start;
            return ToolResult.success(getName(), buildSummary(detail), detail, risk, elapsed);
        } catch (Exception e) {
            log.error("CpuUsageTool 执行失败", e);
            return ToolResult.failure(getName(), e.getMessage());
        }
    }

    // ---- 规则评估 ----

    Map<String, Object> analyze(CpuMetrics m) {
        List<Map<String, String>> findings = new ArrayList<>();
        List<Map<String, String>> suggestions = new ArrayList<>();

        if (m.systemCpuLoad() > cpuProps.getThresholdSystemHigh()) {
            String desc = "系统 CPU 接近饱和，当前: " + FormatUtil.formatPercent(m.systemCpuLoad());
            findings.add(DiagnosticUtils.finding("HIGH", "SystemCpuLoad", desc));
            suggestions.add(DiagnosticUtils.suggestion("HIGH",
                    "建议排查高 CPU 进程，考虑扩容或限制并发连接",
                    "系统 CPU 使用率超过 " + FormatUtil.formatPercent(cpuProps.getThresholdSystemHigh())));
        } else if (m.systemCpuLoad() > cpuProps.getThresholdSystemMedium()) {
            String desc = "系统 CPU 使用率偏高，当前: " + FormatUtil.formatPercent(m.systemCpuLoad());
            findings.add(DiagnosticUtils.finding("MEDIUM", "SystemCpuLoad", desc));
            suggestions.add(DiagnosticUtils.suggestion("MEDIUM",
                    "建议关注 CPU 使用趋势，检查是否有慢查询或频繁 GC",
                    "系统 CPU 使用率超过 " + FormatUtil.formatPercent(cpuProps.getThresholdSystemMedium())));
        }

        if (m.processCpuLoad() > cpuProps.getThresholdProcessHigh()) {
            String desc = "当前 JVM 进程 CPU 占用过高，当前: " + FormatUtil.formatPercent(m.processCpuLoad());
            findings.add(DiagnosticUtils.finding("HIGH", "ProcessCpuLoad", desc));
            suggestions.add(DiagnosticUtils.suggestion("HIGH",
                    "建议分析 JVM 线程堆栈，检查是否有死循环或频繁 Full GC",
                    "进程 CPU 使用率超过 " + FormatUtil.formatPercent(cpuProps.getThresholdProcessHigh())));
        }

        int cores = m.availableProcessors();
        double loadAvg = m.systemLoadAverage();
        if (loadAvg >= 0) {
            if (loadAvg > cores * cpuProps.getThresholdLoadHighMultiplier()) {
                String desc = String.format("系统负载严重超标，load avg: %.2f, cores: %d", loadAvg, cores);
                findings.add(DiagnosticUtils.finding("HIGH", "LoadAverage", desc));
                suggestions.add(DiagnosticUtils.suggestion("HIGH",
                        "系统负载远超 CPU 核心数，建议立即排查高负载进程",
                        "load avg " + String.format("%.2f", loadAvg) + " > " + cores
                                + " × " + cpuProps.getThresholdLoadHighMultiplier()));
            } else if (loadAvg > cores * cpuProps.getThresholdLoadMediumMultiplier()) {
                String desc = String.format("系统负载偏高，load avg: %.2f, cores: %d", loadAvg, cores);
                findings.add(DiagnosticUtils.finding("MEDIUM", "LoadAverage", desc));
                suggestions.add(DiagnosticUtils.suggestion("MEDIUM",
                        "系统负载略高于 CPU 核心数，建议关注",
                        "load avg " + String.format("%.2f", loadAvg) + " > " + cores));
            }
        }

        suggestions = DiagnosticUtils.dedupByAction(suggestions);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("risk", DiagnosticUtils.determineRisk(findings).name());
        detail.put("findings", findings);
        detail.put("suggestions", suggestions);
        detail.put("metrics", Map.of(
                "systemCpuLoad", m.systemCpuLoad(),
                "processCpuLoad", m.processCpuLoad(),
                "systemLoadAverage", m.systemLoadAverage(),
                "availableProcessors", m.availableProcessors()));
        return detail;
    }

    String buildSummary(Map<String, Object> detail) {
        String risk = (String) detail.get("risk");
        @SuppressWarnings("unchecked")
        List<?> findings = (List<?>) detail.get("findings");
        if (findings.isEmpty()) {
            return "CPU 资源使用正常，风险等级 " + risk;
        }
        return "检测到 " + findings.size() + " 个 CPU 资源问题，风险等级 " + risk;
    }
}
