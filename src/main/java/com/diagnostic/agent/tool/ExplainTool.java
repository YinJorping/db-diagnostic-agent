package com.diagnostic.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.regex.Pattern;

@Component
public class ExplainTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ExplainTool.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int TIMEOUT_SECONDS = 5;

    /** 仅匹配以 SELECT 开头的单语句，禁止多语句注入 */
    private static final Pattern SELECT_PATTERN =
            Pattern.compile("^\\s*SELECT\\b", Pattern.CASE_INSENSITIVE);

    private final DataSource dataSource;

    public ExplainTool(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String getName() {
        return "ExplainTool";
    }

    @Override
    public String getDescription() {
        return "对 SQL 执行 EXPLAIN 分析执行计划，识别全表扫描和排序问题，返回风险等级和优化建议";
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String sql = (String) params.get("sql");
        long start = System.currentTimeMillis();

        // ---- 安全校验 ----
        if (sql == null || sql.isBlank()) {
            return ToolResult.failure(getName(), "参数 sql 不能为空");
        }
        if (sql.contains(";")) {
            return ToolResult.failure(getName(), "禁止多语句");
        }
        if (!SELECT_PATTERN.matcher(sql).find()) {
            return ToolResult.failure(getName(), "仅支持 SELECT 语句");
        }

        try {
            String explainJson = runExplain(sql);
            JsonNode plan = parsePlan(explainJson);
            Map<String, Object> detail = analyzePlan(plan);

            long elapsed = System.currentTimeMillis() - start;
            return ToolResult.success(getName(), buildSummary(detail), detail, elapsed);
        } catch (Exception e) {
            log.error("ExplainTool 执行失败: sql={}", sql, e);
            return ToolResult.failure(getName(), e.getMessage());
        }
    }

    // ---- EXPLAIN 执行 ----

    String runExplain(String sql) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.setQueryTimeout(TIMEOUT_SECONDS);
            try (ResultSet rs = stmt.executeQuery("EXPLAIN (FORMAT JSON) " + sql)) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    // ---- JSON 解析 ----

    JsonNode parsePlan(String explainJson) throws JsonProcessingException {
        JsonNode root = mapper.readTree(explainJson);
        // EXPLAIN (FORMAT JSON) 返回 [{ "Plan": {...} }]
        if (root.isArray() && root.size() > 0) {
            return root.get(0).get("Plan");
        }
        return root.get("Plan");
    }

    // ---- V1 规则（4 条） ----

    Map<String, Object> analyzePlan(JsonNode plan) {
        List<Map<String, String>> findings = new ArrayList<>();
        List<Map<String, String>> suggestions = new ArrayList<>();
        String nodeType = plan.path("Node Type").asText();
        long rows = plan.path("Plan Rows").asLong(0);
        String table = plan.path("Relation Name").asText();

        // Rule 1: Seq Scan + rows > 10000 → HIGH
        if ("Seq Scan".equals(nodeType) && rows > 10000) {
            findings.add(finding("HIGH", nodeType, table, rows,
                    table + " 全表扫描，预估扫描 " + rows + " 行"));
            String filterCol = extractFilterColumn(plan);
            if (!filterCol.isEmpty()) {
                suggestions.add(suggestion("HIGH",
                        "建议为 " + filterCol + " 字段建立索引",
                        filterCol + " 列用于 WHERE 过滤但缺少索引"));
            }
        }

        // Rule 2: Seq Scan + 1000 ~ 10000 → MEDIUM
        else if ("Seq Scan".equals(nodeType) && rows >= 1000) {
            findings.add(finding("MEDIUM", nodeType, table, rows,
                    table + " 中等规模全表扫描，预估扫描 " + rows + " 行"));
            String filterCol = extractFilterColumn(plan);
            if (!filterCol.isEmpty()) {
                suggestions.add(suggestion("MEDIUM",
                        "建议为 " + filterCol + " 字段建立索引",
                        filterCol + " 列用于 WHERE 过滤"));
            }
        }

        // Rule 3: Sort + rows > 5000 → MEDIUM
        if ("Sort".equals(nodeType) && rows > 5000) {
            String sortKey = plan.path("Sort Key").toString();
            findings.add(finding("MEDIUM", nodeType, null, rows,
                    "Sort 节点排序 " + rows + " 行，Sort Key: " + sortKey));
            suggestions.add(suggestion("MEDIUM",
                    "建议为排序字段建立索引以优化 ORDER BY",
                    "排序字段缺少索引，导致 Using filesort"));
        }

        // Rule 4: Index Scan → LOW（不产出 finding）

        suggestions = dedupByAction(suggestions);
        String risk = determineRisk(findings);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("risk", risk);
        detail.put("findings", findings);
        detail.put("suggestions", suggestions);
        detail.put("explainJson", plan);
        return detail;
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

    private Map<String, String> finding(String level, String nodeType, String table, long rows, String desc) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("nodeType", nodeType);
        m.put("table", table);
        m.put("estimatedRows", String.valueOf(rows));
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

    private String extractFilterColumn(JsonNode plan) {
        String filter = plan.path("Filter").asText();
        if (filter.isEmpty()) return "";
        int paren = filter.indexOf('(');
        int eq = filter.indexOf('=', paren > -1 ? paren : 0);
        if (paren > -1 && eq > paren) {
            return filter.substring(paren + 1, eq).trim();
        }
        return "";
    }

    String buildSummary(Map<String, Object> detail) {
        String risk = (String) detail.get("risk");
        @SuppressWarnings("unchecked")
        List<?> findings = (List<?>) detail.get("findings");
        if (findings.isEmpty()) {
            return "未发现明显性能问题，风险等级 " + risk;
        }
        return "检测到 " + findings.size() + " 个问题，风险等级 " + risk;
    }

    private String determineRisk(List<Map<String, String>> findings) {
        boolean hasHigh = findings.stream().anyMatch(f -> "HIGH".equals(f.get("level")));
        boolean hasMedium = findings.stream().anyMatch(f -> "MEDIUM".equals(f.get("level")));
        if (hasHigh) return "HIGH";
        if (hasMedium) return "MEDIUM";
        return "LOW";
    }
}
