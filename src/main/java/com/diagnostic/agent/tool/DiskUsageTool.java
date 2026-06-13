package com.diagnostic.agent.tool;

import com.diagnostic.agent.common.util.FormatUtil;
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

@Component
public class DiskUsageTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DiskUsageTool.class);

    private final DiskMetricsProvider provider;
    private final DiskProperties diskProps;
    private final DataSource dataSource;

    public DiskUsageTool(DiskMetricsProvider provider, DiskProperties diskProps, DataSource dataSource) {
        this.provider = provider;
        this.diskProps = diskProps;
        this.dataSource = dataSource;
    }

    @Override
    public String getName() {
        return "DiskUsageTool";
    }

    @Override
    public String getDescription() {
        return "采集数据目录磁盘空间和数据库 I/O 统计，评估磁盘资源健康度";
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        long start = System.currentTimeMillis();
        try {
            DiskMetrics metrics = provider.sample();
            List<DbIoStats> ioStats = queryDbIoStats();
            Map<String, Object> detail = analyze(metrics, ioStats);
            RiskLevel risk = RiskLevel.valueOf((String) detail.get("risk"));
            long elapsed = System.currentTimeMillis() - start;
            return ToolResult.success(getName(), buildSummary(detail), detail, risk, elapsed);
        } catch (Exception e) {
            log.error("DiskUsageTool 执行失败", e);
            return ToolResult.failure(getName(), e.getMessage());
        }
    }

    // ---- PG I/O 统计 ----

    record DbIoStats(String datname, long blksHit, long blksRead,
                     long blkReadTime, long blkWriteTime,
                     long tempFiles, long tempBytes) {}

    List<DbIoStats> queryDbIoStats() {
        String sql = "SELECT datname, blks_hit, blks_read, blk_read_time, blk_write_time, "
                + "temp_files, temp_bytes "
                + "FROM pg_stat_database WHERE datname NOT IN ('template0', 'template1')";
        List<DbIoStats> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(new DbIoStats(
                        rs.getString("datname"),
                        rs.getLong("blks_hit"),
                        rs.getLong("blks_read"),
                        rs.getLong("blk_read_time"),
                        rs.getLong("blk_write_time"),
                        rs.getLong("temp_files"),
                        rs.getLong("temp_bytes")));
            }
        } catch (SQLException e) {
            log.warn("查询 pg_stat_database I/O 统计失败: {}", e.getMessage());
        }
        return result;
    }

    // ---- 规则评估 ----

    Map<String, Object> analyze(DiskMetrics m, List<DbIoStats> ioStats) {
        List<Map<String, String>> findings = new ArrayList<>();
        List<Map<String, String>> suggestions = new ArrayList<>();

        if (m.totalBytes() > 0) {
            long usedBytes = m.totalBytes() - m.usableBytes();
            double usageRatio = (double) usedBytes / m.totalBytes();

            // R1: 使用率 > HIGH 阈值
            if (usageRatio > diskProps.getThresholdUsageHigh()) {
                String desc = String.format("数据目录磁盘使用率严重过高: %.1f%% (%d / %d GB)",
                        usageRatio * 100,
                        usedBytes / (1024 * 1024 * 1024),
                        m.totalBytes() / (1024 * 1024 * 1024));
                findings.add(DiagnosticUtils.finding("HIGH", "DiskUsage", desc));
                suggestions.add(DiagnosticUtils.suggestion("HIGH",
                        "磁盘使用率超过 " + FormatUtil.formatPercent(diskProps.getThresholdUsageHigh())
                                + "，建议立即清理旧数据、WAL 归档或扩容磁盘",
                        "使用率 " + FormatUtil.formatPercent(usageRatio)
                                + " (可用 " + (m.usableBytes() / (1024 * 1024 * 1024)) + " GB)"));
            } else if (usageRatio > diskProps.getThresholdUsageMedium()) {
                String desc = String.format("数据目录磁盘使用率偏高: %.1f%% (%d / %d GB)",
                        usageRatio * 100,
                        usedBytes / (1024 * 1024 * 1024),
                        m.totalBytes() / (1024 * 1024 * 1024));
                findings.add(DiagnosticUtils.finding("MEDIUM", "DiskUsage", desc));
                suggestions.add(DiagnosticUtils.suggestion("MEDIUM",
                        "磁盘使用率超过 " + FormatUtil.formatPercent(diskProps.getThresholdUsageMedium())
                                + "，建议关注增长趋势，规划扩容",
                        "使用率 " + FormatUtil.formatPercent(usageRatio)));
            }

            // R3: 可用空间 < 绝对阈值
            if (m.usableBytes() < diskProps.getThresholdFreeBytesLow()) {
                String desc = String.format("数据目录可用空间严重不足: %d GB",
                        m.usableBytes() / (1024 * 1024 * 1024));
                findings.add(DiagnosticUtils.finding("HIGH", "DiskFreeSpace", desc));
                suggestions.add(DiagnosticUtils.suggestion("HIGH",
                        "可用空间不足 " + (diskProps.getThresholdFreeBytesLow() / (1024 * 1024 * 1024))
                                + " GB，有宕机风险，请立即扩容或清理",
                        "可用 " + (m.usableBytes() / (1024 * 1024 * 1024)) + " GB < "
                                + (diskProps.getThresholdFreeBytesLow() / (1024 * 1024 * 1024)) + " GB"));
            }
        }

        suggestions = DiagnosticUtils.dedupByAction(suggestions);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("risk", DiagnosticUtils.determineRisk(findings).name());
        detail.put("findings", findings);
        detail.put("suggestions", suggestions);
        detail.put("diskMetrics", Map.of(
                "dataDirPath", m.dataDirPath(),
                "totalGB", m.totalBytes() / (1024 * 1024 * 1024),
                "usableGB", m.usableBytes() / (1024 * 1024 * 1024),
                "usedGB", (m.totalBytes() - m.usableBytes()) / (1024 * 1024 * 1024)));

        List<Map<String, Object>> dbIoList = new ArrayList<>();
        for (DbIoStats s : ioStats) {
            Map<String, Object> db = new LinkedHashMap<>();
            db.put("database", s.datname());
            db.put("blksHit", s.blksHit());
            db.put("blksRead", s.blksRead());
            db.put("blkReadTimeMs", s.blkReadTime());
            db.put("blkWriteTimeMs", s.blkWriteTime());
            db.put("tempFiles", s.tempFiles());
            db.put("tempBytes", s.tempBytes());
            dbIoList.add(db);
        }
        detail.put("dbIoStats", dbIoList);

        return detail;
    }

    @SuppressWarnings("unchecked")
    String buildSummary(Map<String, Object> detail) {
        String risk = (String) detail.get("risk");
        List<?> findings = (List<?>) detail.get("findings");
        if (findings.isEmpty()) {
            return "磁盘空间使用正常，风险等级 " + risk;
        }
        return "检测到 " + findings.size() + " 个磁盘问题，风险等级 " + risk;
    }
}
