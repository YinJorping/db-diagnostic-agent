package com.diagnostic.agent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断工具类——finding/suggestion 构建、风险聚合、suggestion 去重。
 * 所有 Tool 共享，避免逐 Tool 复制。
 */
public final class DiagnosticUtils {

    private DiagnosticUtils() {
    }

    /** 创建 finding（3-param 通用版本）。 */
    public static Map<String, String> finding(String level, String nodeType, String desc) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("nodeType", nodeType);
        m.put("description", desc);
        return m;
    }

    /** 创建优化建议。 */
    public static Map<String, String> suggestion(String priority, String action, String reason) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("priority", priority);
        m.put("action", action);
        m.put("reason", reason);
        return m;
    }

    /** 聚合风险等级：HIGH > MEDIUM > LOW。 */
    public static RiskLevel determineRisk(List<Map<String, String>> findings) {
        boolean hasHigh = findings.stream().anyMatch(f -> "HIGH".equals(f.get("level")));
        boolean hasMedium = findings.stream().anyMatch(f -> "MEDIUM".equals(f.get("level")));
        if (hasHigh) return RiskLevel.HIGH;
        if (hasMedium) return RiskLevel.MEDIUM;
        return RiskLevel.LOW;
    }

    /** 按 action 字段去重 suggestions，保留首次出现顺序。 */
    public static List<Map<String, String>> dedupByAction(List<Map<String, String>> suggestions) {
        Map<String, Map<String, String>> dedup = new LinkedHashMap<>();
        for (Map<String, String> s : suggestions) {
            dedup.putIfAbsent(s.get("action"), s);
        }
        return new ArrayList<>(dedup.values());
    }
}
