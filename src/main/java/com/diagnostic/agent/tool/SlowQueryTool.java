package com.diagnostic.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SlowQueryTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(SlowQueryTool.class);
    private static final int TIMEOUT_SECONDS = 5;
    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 100;
    private static final int QUERY_MAX_LENGTH = 500;

    private static final double HIGH_THRESHOLD_MS = 1000.0;
    private static final double MEDIUM_THRESHOLD_MS = 100.0;

    private final DataSource dataSource;

    public SlowQueryTool(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String getName() {
        return "SlowQueryTool";
    }

    @Override
    public String getDescription() {
        return "查询 pg_stat_statements 获取最耗时的 SQL，返回风险等级和优化建议";
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        int limit = parseLimit(params.get("limit"));
        if (limit < 1 || limit > MAX_LIMIT) {
            return ToolResult.failure(getName(),
                    "limit 参数必须在 1-" + MAX_LIMIT + " 之间，当前值: " + limit);
        }

        long start = System.currentTimeMillis();
        try {
            List<Map<String, Object>> rows = querySlowQueries(limit);
            Map<String, Object> detail = analyze(rows);
            long elapsed = System.currentTimeMillis() - start;
            return ToolResult.success(getName(), buildSummary(detail), detail, elapsed);
        } catch (Exception e) {
            log.warn("SlowQueryTool 查询失败: {}", e.getMessage());
            return ToolResult.failure(getName(), mapErrorMessage(e));
        }
    }

    // ---- 参数解析 ----

    int parseLimit(Object limitObj) {
        if (limitObj == null) return DEFAULT_LIMIT;
        if (limitObj instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(limitObj.toString());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---- SQL 构建与执行 ----

    /** package-private 供测试覆写 */
    String buildQuerySql(int limit) {
        return """
                SELECT query, calls, mean_exec_time, total_exec_time, rows
                FROM pg_stat_statements
                WHERE query NOT LIKE '%%pg_stat_statements%%'
                ORDER BY mean_exec_time DESC
                LIMIT %d
                """.formatted(limit);
    }

    List<Map<String, Object>> querySlowQueries(int limit) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setQueryTimeout(TIMEOUT_SECONDS);
            String sql = buildQuerySql(limit);
            ResultSet rs = stmt.executeQuery(sql);
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("query", truncate(rs.getString("query"), QUERY_MAX_LENGTH));
                row.put("calls", rs.getLong("calls"));
                row.put("meanTimeMs", rs.getDouble("mean_exec_time"));
                row.put("totalTimeMs", rs.getDouble("total_exec_time"));
                row.put("rows", rs.getLong("rows"));
                rows.add(row);
            }
            return rows;
        }
    }

    // ---- 分析 ----

    Map<String, Object> analyze(List<Map<String, Object>> rows) {
        List<Map<String, String>> findings = new ArrayList<>();
        List<Map<String, String>> suggestions = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            double meanTimeMs = (double) row.get("meanTimeMs");
            String query = (String) row.get("query");

            String level = classifyRisk(meanTimeMs);
            if (!"LOW".equals(level)) {
                String desc = "慢查询 #" + (i + 1) + " 平均耗时 " + String.format("%.2f", meanTimeMs) + " ms";
                findings.add(finding(level, "SlowQuery", query, (long) row.get("calls"),
                        meanTimeMs, desc));
                suggestions.add(suggestion(level,
                        level.equals("HIGH")
                                ? "该 SQL 平均耗时 " + String.format("%.0f", meanTimeMs) + "ms，建议分析执行计划并优化索引"
                                : "该 SQL 平均耗时 " + String.format("%.0f", meanTimeMs) + "ms，建议关注并分析执行计划",
                        truncate(query, 200)));
            }
        }

        suggestions = dedupByAction(suggestions);
        String risk = determineRisk(findings);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("risk", risk);
        detail.put("findings", findings);
        detail.put("suggestions", suggestions);
        detail.put("slowQueries", rows);
        return detail;
    }

    // ---- 风险分级 ----

    String classifyRisk(double meanTimeMs) {
        if (meanTimeMs > HIGH_THRESHOLD_MS) return "HIGH";
        if (meanTimeMs > MEDIUM_THRESHOLD_MS) return "MEDIUM";
        return "LOW";
    }

    private String determineRisk(List<Map<String, String>> findings) {
        boolean hasHigh = findings.stream().anyMatch(f -> "HIGH".equals(f.get("level")));
        boolean hasMedium = findings.stream().anyMatch(f -> "MEDIUM".equals(f.get("level")));
        if (hasHigh) return "HIGH";
        if (hasMedium) return "MEDIUM";
        return "LOW";
    }

    // ---- 错误处理 ----

    private String mapErrorMessage(Exception e) {
        String msg = e.getMessage();
        if (msg != null && msg.contains("pg_stat_statements")) {
            return "pg_stat_statements 扩展不可用，请确认 shared_preload_libraries 包含 pg_stat_statements";
        }
        return msg != null ? msg : "查询失败";
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

    private Map<String, String> finding(String level, String nodeType, String query,
                                        long calls, double meanTimeMs, String desc) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("nodeType", nodeType);
        m.put("query", truncate(query, QUERY_MAX_LENGTH));
        m.put("calls", String.valueOf(calls));
        m.put("meanTimeMs", String.format("%.2f", meanTimeMs));
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

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    String buildSummary(Map<String, Object> detail) {
        String risk = (String) detail.get("risk");
        @SuppressWarnings("unchecked")
        List<?> findings = (List<?>) detail.get("findings");
        if (findings.isEmpty()) {
            return "未发现慢查询，风险等级 " + risk;
        }
        return "检测到 " + findings.size() + " 条慢查询，风险等级 " + risk;
    }
}
