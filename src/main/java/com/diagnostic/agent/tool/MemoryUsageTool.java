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
public class MemoryUsageTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(MemoryUsageTool.class);

    private final DataSource dataSource;
    private final MemoryProperties memoryProps;

    public MemoryUsageTool(DataSource dataSource, MemoryProperties memoryProps) {
        this.dataSource = dataSource;
        this.memoryProps = memoryProps;
    }

    @Override
    public String getName() {
        return "MemoryUsageTool";
    }

    @Override
    public String getDescription() {
        return "采集数据库缓冲命中率和内存配置，评估内存资源健康度";
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        long start = System.currentTimeMillis();
        try {
            List<DbBufferStats> stats = queryBufferMetrics();
            Map<String, SettingValue> settings = queryMemorySettings();
            Map<String, Object> detail = analyze(stats, settings);
            RiskLevel risk = RiskLevel.valueOf((String) detail.get("risk"));
            long elapsed = System.currentTimeMillis() - start;
            return ToolResult.success(getName(), buildSummary(detail), detail, risk, elapsed);
        } catch (Exception e) {
            log.error("MemoryUsageTool 执行失败", e);
            return ToolResult.failure(getName(), e.getMessage());
        }
    }

    // ---- 数据采集 ----

    record DbBufferStats(String datname, long blksHit, long blksRead,
                         long tempFiles, long tempBytes) {}

    record SettingValue(String name, long valueMB) {}

    List<DbBufferStats> queryBufferMetrics() {
        String sql = "SELECT datname, blks_hit, blks_read, temp_files, temp_bytes "
                + "FROM pg_stat_database WHERE datname NOT IN ('template0', 'template1')";
        List<DbBufferStats> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                result.add(new DbBufferStats(
                        rs.getString("datname"),
                        rs.getLong("blks_hit"),
                        rs.getLong("blks_read"),
                        rs.getLong("temp_files"),
                        rs.getLong("temp_bytes")));
            }
        } catch (SQLException e) {
            log.warn("查询 pg_stat_database 失败: {}", e.getMessage());
        }
        return result;
    }

    Map<String, SettingValue> queryMemorySettings() {
        String sql = "SELECT name, setting, unit FROM pg_settings WHERE name IN ('shared_buffers', 'work_mem')";
        Map<String, SettingValue> result = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String name = rs.getString("name");
                long bytes = parseToBytes(rs.getString("setting"), rs.getString("unit"));
                long mb = bytes / (1024 * 1024);
                result.put(name, new SettingValue(name, mb));
            }
        } catch (SQLException e) {
            log.warn("查询 pg_settings 失败: {}", e.getMessage());
        }
        return result;
    }

    static long parseToBytes(String settingValue, String unit) {
        try {
            double value = Double.parseDouble(settingValue);
            String u = unit == null ? "b" : unit.toLowerCase();
            return switch (u) {
                case "b" -> (long) value;
                case "kb" -> (long) (value * 1024);
                case "mb" -> (long) (value * 1024 * 1024);
                case "gb" -> (long) (value * 1024 * 1024 * 1024);
                case "8kb" -> (long) (value * 8 * 1024);
                default -> {
                    log.warn("未知的 pg_settings unit: {}, 值: {}, 将返回 0", unit, settingValue);
                    yield 0;
                }
            };
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ---- 规则评估 ----

    Map<String, Object> analyze(List<DbBufferStats> stats, Map<String, SettingValue> settings) {
        List<Map<String, String>> findings = new ArrayList<>();
        List<Map<String, String>> suggestions = new ArrayList<>();

        for (DbBufferStats s : stats) {
            long totalBlocks = s.blksHit() + s.blksRead();
            if (totalBlocks == 0) continue;

            double hitRatio = (double) s.blksHit() / totalBlocks;

            if (hitRatio < memoryProps.getThresholdBufferHitMedium()) {
                String desc = String.format("%s 缓存命中率严重过低: %.1f%%", s.datname(), hitRatio * 100);
                findings.add(DiagnosticUtils.finding("HIGH", "BufferHitRatio", desc));
                suggestions.add(DiagnosticUtils.suggestion("HIGH",
                        "建议增大 shared_buffers 或检查是否有大量顺序扫描",
                        s.datname() + " 缓存命中率 " + FormatUtil.formatPercent(hitRatio)
                                + " < " + FormatUtil.formatPercent(memoryProps.getThresholdBufferHitMedium())));
            } else if (hitRatio < memoryProps.getThresholdBufferHitHigh()) {
                String desc = String.format("%s 缓存命中率偏低: %.1f%%", s.datname(), hitRatio * 100);
                findings.add(DiagnosticUtils.finding("MEDIUM", "BufferHitRatio", desc));
                suggestions.add(DiagnosticUtils.suggestion("MEDIUM",
                        "建议关注缓存命中率趋势，评估是否需要增大 shared_buffers",
                        s.datname() + " 缓存命中率 " + FormatUtil.formatPercent(hitRatio)
                                + " < " + FormatUtil.formatPercent(memoryProps.getThresholdBufferHitHigh())));
            }
        }

        for (DbBufferStats s : stats) {
            if (s.tempFiles() > memoryProps.getThresholdTempFilesCount()
                    || s.tempBytes() > memoryProps.getThresholdTempBytesBytes()) {
                String desc = String.format("%s 临时文件: %d 个, %d bytes",
                        s.datname(), s.tempFiles(), s.tempBytes());
                findings.add(DiagnosticUtils.finding("MEDIUM", "TempFiles", desc));
                suggestions.add(DiagnosticUtils.suggestion("MEDIUM",
                        "临时文件过多，建议增大 work_mem 以减少磁盘排序",
                        s.datname() + " temp_files=" + s.tempFiles()
                                + " 超过阈值 " + memoryProps.getThresholdTempFilesCount()));
                break;
            }
        }

        SettingValue sb = settings.get("shared_buffers");
        if (sb != null && sb.valueMB() < memoryProps.getThresholdSharedBuffersMB()) {
            String desc = String.format("shared_buffers 配置过低: %d MB", sb.valueMB());
            findings.add(DiagnosticUtils.finding("MEDIUM", "SharedBuffers", desc));
            suggestions.add(DiagnosticUtils.suggestion("MEDIUM",
                    "建议增大 shared_buffers（通常为系统内存的 25%）",
                    "shared_buffers " + sb.valueMB() + " MB < " + memoryProps.getThresholdSharedBuffersMB() + " MB"));
        }

        SettingValue wm = settings.get("work_mem");
        if (wm != null && wm.valueMB() > memoryProps.getThresholdWorkMemMB()) {
            String desc = String.format("work_mem 配置过高: %d MB", wm.valueMB());
            findings.add(DiagnosticUtils.finding("MEDIUM", "WorkMem", desc));
            suggestions.add(DiagnosticUtils.suggestion("MEDIUM",
                    "work_mem 值较大，并发排序查询时可能耗尽内存",
                    "work_mem " + wm.valueMB() + " MB > " + memoryProps.getThresholdWorkMemMB() + " MB"));
        }

        suggestions = DiagnosticUtils.dedupByAction(suggestions);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("risk", DiagnosticUtils.determineRisk(findings).name());
        detail.put("findings", findings);
        detail.put("suggestions", suggestions);

        List<Map<String, Object>> dbMetrics = new ArrayList<>();
        for (DbBufferStats s : stats) {
            long total = s.blksHit() + s.blksRead();
            double ratio = total > 0 ? (double) s.blksHit() / total : -1;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("database", s.datname());
            m.put("blksHit", s.blksHit());
            m.put("blksRead", s.blksRead());
            m.put("hitRatio", ratio);
            m.put("tempFiles", s.tempFiles());
            m.put("tempBytes", s.tempBytes());
            dbMetrics.add(m);
        }
        detail.put("databases", dbMetrics);
        detail.put("settings", settings.values().stream()
                .map(s -> Map.of("name", s.name(), "valueMB", s.valueMB()))
                .toList());
        return detail;
    }

    @SuppressWarnings("unchecked")
    String buildSummary(Map<String, Object> detail) {
        String risk = (String) detail.get("risk");
        List<?> findings = (List<?>) detail.get("findings");
        if (findings.isEmpty()) {
            return "内存配置及缓存命中率正常，风险等级 " + risk;
        }
        return "检测到 " + findings.size() + " 个内存问题，风险等级 " + risk;
    }
}
