package com.diagnostic.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class JvmUsageTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(JvmUsageTool.class);

    private final JvmMetricsProvider provider;
    private final JvmProperties jvmProps;

    public JvmUsageTool(JvmMetricsProvider provider, JvmProperties jvmProps) {
        this.provider = provider;
        this.jvmProps = jvmProps;
    }

    @Override
    public String getName() {
        return "JvmUsageTool";
    }

    @Override
    public String getDescription() {
        return "采集 JVM 堆内存、非堆内存、GC 和线程指标，评估 JVM 资源健康度";
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        long start = System.currentTimeMillis();
        try {
            JvmMetrics metrics = provider.sample();
            Map<String, Object> detail = analyze(metrics);
            RiskLevel risk = RiskLevel.valueOf((String) detail.get("risk"));
            long elapsed = System.currentTimeMillis() - start;
            return ToolResult.success(getName(), buildSummary(detail), detail, risk, elapsed);
        } catch (Exception e) {
            log.error("JvmUsageTool 执行失败", e);
            return ToolResult.failure(getName(), e.getMessage());
        }
    }

    // ---- 规则评估 ----

    Map<String, Object> analyze(JvmMetrics m) {
        List<Map<String, String>> findings = new ArrayList<>();
        List<Map<String, String>> suggestions = new ArrayList<>();

        // R1/R2: 堆内存使用率
        if (m.heapMaxBytes() > 0) {
            double heapRatio = (double) m.heapUsedBytes() / m.heapMaxBytes();
            if (heapRatio > jvmProps.getThresholdHeapHigh()) {
                String desc = String.format("堆内存接近耗尽: %.1f%% (%d / %d MB)",
                        heapRatio * 100,
                        m.heapUsedBytes() / (1024 * 1024),
                        m.heapMaxBytes() / (1024 * 1024));
                findings.add(finding("HIGH", "HeapUsage", desc));
                suggestions.add(suggestion("HIGH",
                        "堆内存使用率超过 " + formatPercent(jvmProps.getThresholdHeapHigh())
                                + "，建议排查内存泄漏或增大 -Xmx",
                        "堆使用率 " + formatPercent(heapRatio)));
            } else if (heapRatio > jvmProps.getThresholdHeapMedium()) {
                String desc = String.format("堆内存使用率偏高: %.1f%% (%d / %d MB)",
                        heapRatio * 100,
                        m.heapUsedBytes() / (1024 * 1024),
                        m.heapMaxBytes() / (1024 * 1024));
                findings.add(finding("MEDIUM", "HeapUsage", desc));
                suggestions.add(suggestion("MEDIUM",
                        "堆内存使用率超过 " + formatPercent(jvmProps.getThresholdHeapMedium())
                                + "，建议关注 GC 频率和内存趋势",
                        "堆使用率 " + formatPercent(heapRatio)));
            }
        }

        // R3: 非堆内存使用率
        if (m.nonHeapCommittedBytes() > 0) {
            double nonHeapRatio = (double) m.nonHeapUsedBytes() / m.nonHeapCommittedBytes();
            if (nonHeapRatio > jvmProps.getThresholdNonHeapHigh()) {
                String desc = String.format("非堆内存（Metaspace）接近上限: %.1f%% (%d / %d MB)",
                        nonHeapRatio * 100,
                        m.nonHeapUsedBytes() / (1024 * 1024),
                        m.nonHeapCommittedBytes() / (1024 * 1024));
                findings.add(finding("HIGH", "NonHeapUsage", desc));
                suggestions.add(suggestion("HIGH",
                        "Metaspace 使用率超过 " + formatPercent(jvmProps.getThresholdNonHeapHigh())
                                + "，建议检查类加载泄漏或增大 -XX:MaxMetaspaceSize",
                        "非堆使用率 " + formatPercent(nonHeapRatio)));
            }
        }

        // R4: 线程数
        if (m.threadCount() > jvmProps.getThresholdThreadCount()) {
            String desc = String.format("线程数过多: %d (peak: %d, daemon: %d)",
                    m.threadCount(), m.peakThreadCount(), m.daemonThreadCount());
            findings.add(finding("MEDIUM", "ThreadCount", desc));
            suggestions.add(suggestion("MEDIUM",
                    "当前线程数 " + m.threadCount() + " 超过阈值 "
                            + jvmProps.getThresholdThreadCount() + "，可能存在线程泄漏",
                    "threadCount " + m.threadCount() + " > " + jvmProps.getThresholdThreadCount()));
        }

        suggestions = dedupByAction(suggestions);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("risk", determineRisk(findings).name());
        detail.put("findings", findings);
        detail.put("suggestions", suggestions);
        detail.put("metrics", Map.of(
                "heapUsedMB", m.heapUsedBytes() / (1024 * 1024),
                "heapMaxMB", m.heapMaxBytes() / (1024 * 1024),
                "heapMaxSet", m.heapMaxBytes() > 0,
                "nonHeapUsedMB", m.nonHeapUsedBytes() / (1024 * 1024),
                "nonHeapCommittedMB", m.nonHeapCommittedBytes() / (1024 * 1024),
                "threadCount", m.threadCount(),
                "peakThreadCount", m.peakThreadCount(),
                "daemonThreadCount", m.daemonThreadCount(),
                "uptimeMinutes", m.uptimeMs() / 60000));

        // GC 数据仅展示
        List<Map<String, Object>> gcList = new ArrayList<>();
        for (GcSnapshot gc : m.gcSnapshots()) {
            gcList.add(Map.of(
                    "name", gc.name(),
                    "collectionCount", gc.collectionCount(),
                    "collectionTimeMs", gc.collectionTimeMs()));
        }
        detail.put("gcSnapshots", gcList);

        return detail;
    }

    // ---- Risk 聚合 ----

    RiskLevel determineRisk(List<Map<String, String>> findings) {
        boolean hasHigh = findings.stream().anyMatch(f -> "HIGH".equals(f.get("level")));
        boolean hasMedium = findings.stream().anyMatch(f -> "MEDIUM".equals(f.get("level")));
        if (hasHigh) return RiskLevel.HIGH;
        if (hasMedium) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    // ---- 格式化 ----

    @SuppressWarnings("unchecked")
    String buildSummary(Map<String, Object> detail) {
        String risk = (String) detail.get("risk");
        List<?> findings = (List<?>) detail.get("findings");
        if (findings.isEmpty()) {
            return "JVM 资源使用正常，风险等级 " + risk;
        }
        return "检测到 " + findings.size() + " 个 JVM 资源问题，风险等级 " + risk;
    }

    private static String formatPercent(double v) {
        return String.format("%.0f%%", v * 100);
    }

    // ---- 去重 ----

    List<Map<String, String>> dedupByAction(List<Map<String, String>> suggestions) {
        Map<String, Map<String, String>> dedup = new LinkedHashMap<>();
        for (Map<String, String> s : suggestions) {
            dedup.putIfAbsent(s.get("action"), s);
        }
        return new ArrayList<>(dedup.values());
    }

    // ---- Helpers ----

    private Map<String, String> finding(String level, String nodeType, String desc) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("nodeType", nodeType);
        m.put("description", desc);
        return m;
    }

    private Map<String, String> suggestion(String priority, String action, String reason) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("priority", priority);
        m.put("action", action);
        m.put("reason", reason);
        return m;
    }
}
