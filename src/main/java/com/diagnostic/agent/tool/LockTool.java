package com.diagnostic.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 锁阻塞检测 + 连接状态快照 (V1 Scope C5 + Section 2 跨域能力).
 * 每次诊断由 Orchestrator 自动前置采集，不作为独立 Agent 的专属 Tool.
 */
@Component
public class LockTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(LockTool.class);
    private static final int LONG_TXN_MINUTES = 5;

    private final DataSource dataSource;

    public LockTool(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public String getName() {
        return "LockTool";
    }

    @Override
    public String getDescription() {
        return "检测锁阻塞等待链和数据库连接状态快照";
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        long start = System.currentTimeMillis();
        try {
            List<Map<String, Object>> lockBlocks = queryLockBlocks();
            Map<String, Object> connSnapshot = queryConnectionSnapshot();
            Map<String, Object> detail = analyze(lockBlocks, connSnapshot);
            RiskLevel risk = RiskLevel.valueOf((String) detail.get("risk"));
            long elapsed = System.currentTimeMillis() - start;
            return ToolResult.success(getName(), buildSummary(detail), detail, risk, elapsed);
        } catch (Exception e) {
            log.warn("LockTool 执行失败: {}", e.getMessage());
            return ToolResult.failure(getName(), e.getMessage());
        }
    }

    // ---- pg_locks — 锁阻塞等待链 ----

    List<Map<String, Object>> queryLockBlocks() {
        String sql = """
                SELECT
                    blocked.pid              AS blocked_pid,
                    blocked.query            AS blocked_query,
                    blocked.state            AS blocked_state,
                    extract(epoch from (now() - blocked_locks.waitstart)) AS wait_seconds,
                    blocking.pid             AS blocking_pid,
                    blocking.query           AS blocking_query,
                    blocking.state           AS blocking_state,
                    blocked_locks.mode       AS blocked_mode,
                    blocked_locks.locktype,
                    blocked_locks.relation::regclass::text AS locked_relation
                FROM pg_locks blocked_locks
                JOIN pg_stat_activity blocked
                    ON blocked.pid = blocked_locks.pid
                JOIN pg_locks blocking_locks
                    ON blocking_locks.locktype = blocked_locks.locktype
                    AND blocking_locks.relation = blocked_locks.relation
                    AND blocking_locks.pid != blocked_locks.pid
                JOIN pg_stat_activity blocking
                    ON blocking.pid = blocking_locks.pid
                WHERE NOT blocked_locks.granted
                    AND blocked_locks.relation IS NOT NULL
                ORDER BY blocked_locks.waitstart
                """;
        return queryList(sql);
    }

    // ---- pg_stat_activity — 连接状态快照 ----

    Map<String, Object> queryConnectionSnapshot() {
        String sql = """
                SELECT
                    count(*)                                                   AS total,
                    count(*) FILTER (WHERE state = 'active')                   AS active,
                    count(*) FILTER (WHERE state = 'idle')                     AS idle,
                    count(*) FILTER (WHERE state = 'idle in transaction')      AS idle_in_txn,
                    count(*) FILTER (WHERE wait_event IS NOT NULL)             AS waiting,
                    count(*) FILTER (WHERE xact_start < now() - interval '%d minutes'
                                         AND state != 'idle')                  AS long_txns
                FROM pg_stat_activity
                WHERE backend_type = 'client backend'
                """.formatted(LONG_TXN_MINUTES);
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                Map<String, Object> snap = new LinkedHashMap<>();
                snap.put("totalConnections", rs.getLong("total"));
                snap.put("active", rs.getLong("active"));
                snap.put("idle", rs.getLong("idle"));
                snap.put("idleInTransaction", rs.getLong("idle_in_txn"));
                snap.put("waiting", rs.getLong("waiting"));
                snap.put("longTransactions", rs.getLong("long_txns"));
                return snap;
            }
        } catch (SQLException e) {
            log.warn("查询连接状态快照失败: {}", e.getMessage());
        }
        return Map.of();
    }

    // ---- 分析 ----

    Map<String, Object> analyze(List<Map<String, Object>> lockBlocks,
                                Map<String, Object> connSnapshot) {
        List<Map<String, String>> findings = new ArrayList<>();
        List<Map<String, String>> suggestions = new ArrayList<>();

        // 锁阻塞检测
        for (Map<String, Object> block : lockBlocks) {
            String desc = String.format(
                    "PID %s (%s) 等待 %s 锁, 已等待 %.0fs, 被 PID %s (%s) 阻塞",
                    block.get("blocked_pid"), block.get("blocked_state"),
                    block.get("blocked_mode"),
                    block.get("wait_seconds"),
                    block.get("blocking_pid"), block.get("blocking_state"));
            findings.add(DiagnosticUtils.finding("HIGH", "LockBlock", desc));
            suggestions.add(DiagnosticUtils.suggestion("HIGH",
                    "建议检查 PID " + block.get("blocking_pid")
                            + " 的查询是否需要终止，或排查应用层事务提交逻辑",
                    "PID " + block.get("blocked_pid") + " 等待锁超过 "
                            + block.get("wait_seconds") + "s"));
        }

        // 连接状态快照
        if (!connSnapshot.isEmpty()) {
            long total = (long) connSnapshot.get("totalConnections");
            long active = (long) connSnapshot.get("active");
            long idleInTxn = (long) connSnapshot.get("idleInTransaction");
            long waiting = (long) connSnapshot.get("waiting");
            long longTxns = (long) connSnapshot.get("longTransactions");

            if (idleInTxn > 0) {
                String desc = "检测到 " + idleInTxn + " 个 idle-in-transaction 连接，"
                        + "可能持有锁未释放，总连接数 " + total;
                findings.add(DiagnosticUtils.finding("MEDIUM", "IdleInTransaction", desc));
                suggestions.add(DiagnosticUtils.suggestion("MEDIUM",
                        "idle-in-transaction 连接是常见的锁阻塞根因，"
                                + "建议检查应用层事务管理，确保事务及时提交或回滚",
                        "idle-in-transaction 连接数: " + idleInTxn));
            }
            if (longTxns > 0) {
                String desc = "检测到 " + longTxns + " 个长事务 (> " + LONG_TXN_MINUTES
                        + "min)，可能阻塞 VACUUM 并导致表膨胀";
                findings.add(DiagnosticUtils.finding("MEDIUM", "LongTransaction", desc));
                suggestions.add(DiagnosticUtils.suggestion("MEDIUM",
                        "长事务会阻止 VACUUM 清理死元组，"
                                + "建议排查应用层是否有未关闭的事务",
                        "长事务数: " + longTxns));
            }
            // 连接状态概览（非问题，仅信息）
            if (findings.isEmpty() && total > 0) {
                String desc = String.format("连接状态正常: 共 %d 连接 (活跃 %d, 空闲 %d, 等待 %d)",
                        total, active, total - active - idleInTxn, waiting);
                findings.add(DiagnosticUtils.finding("LOW", "ConnectionSnapshot", desc));
            }
        }

        suggestions = DiagnosticUtils.dedupByAction(suggestions);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("risk", DiagnosticUtils.determineRisk(findings).name());
        detail.put("findings", findings);
        detail.put("suggestions", suggestions);
        detail.put("lockBlocks", lockBlocks);
        detail.put("connectionSnapshot", connSnapshot);
        return detail;
    }

    // ---- Helpers ----

    @SuppressWarnings("unchecked")
    String buildSummary(Map<String, Object> detail) {
        String risk = (String) detail.get("risk");
        List<?> findings = (List<?>) detail.get("findings");
        List<?> lockBlocks = (List<?>) detail.get("lockBlocks");
        int blockCount = lockBlocks != null ? lockBlocks.size() : 0;

        if (findings.isEmpty()) {
            return "未检测到锁阻塞或连接异常，风险等级 " + risk;
        }
        if (blockCount > 0) {
            return "检测到 " + blockCount + " 个锁等待，风险等级 " + risk;
        }
        return "连接状态分析完成，发现 " + findings.size() + " 个关注项，风险等级 " + risk;
    }

    private List<Map<String, Object>> queryList(String sql) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    row.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
                }
                rows.add(row);
            }
        } catch (SQLException e) {
            log.warn("LockTool 查询失败: {}", e.getMessage());
        }
        return rows;
    }
}
