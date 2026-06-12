package com.diagnostic.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiskUsageToolTest {

    @Mock private DiskMetricsProvider provider;
    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private PreparedStatement stmt;

    private DiskUsageTool tool;

    @BeforeEach
    void setup() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(stmt);
        tool = new DiskUsageTool(provider, new DiskProperties(), dataSource);
    }

    // ---- 工具基本属性 ----

    @Test
    void shouldReturnCorrectToolName() {
        assertThat(tool.getName()).isEqualTo("DiskUsageTool");
    }

    @Test
    void shouldReturnNonEmptyDescription() {
        assertThat(tool.getDescription()).isNotBlank();
    }

    // ---- 正常场景 ----

    @Test
    void shouldReturnLowRiskWhenDiskIsHealthy() throws Exception {
        when(provider.sample()).thenReturn(new DiskMetrics("/data", 100L * GB, 50L * GB));
        mockNoRows();

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.LOW);
        assertThat(result.getSummary()).contains("LOW");
    }

    // ---- R1: usageRatio > 0.85 → HIGH ----

    @Test
    void shouldDetectHighDiskUsage() throws Exception {
        when(provider.sample()).thenReturn(new DiskMetrics("/data", 100L * GB, 8L * GB));
        mockNoRows();

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.HIGH);
        assertThat(result.getSummary()).contains("HIGH");
    }

    // ---- R2: usageRatio > 0.70 → MEDIUM ----

    @Test
    void shouldDetectMediumDiskUsage() throws Exception {
        when(provider.sample()).thenReturn(new DiskMetrics("/data", 100L * GB, 22L * GB));
        mockNoRows();

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.MEDIUM);
    }

    // ---- R3: usableBytes < 10GB → HIGH ----

    @Test
    void shouldDetectLowFreeSpace() throws Exception {
        when(provider.sample()).thenReturn(new DiskMetrics("/data", 100L * GB, 5L * GB));
        mockNoRows();

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.HIGH);
    }

    // ---- totalBytes=0 → 跳过所有规则 ----

    @Test
    void shouldSkipAllRulesWhenTotalBytesIsZero() throws Exception {
        when(provider.sample()).thenReturn(new DiskMetrics("/data", 0, 0));
        mockNoRows();

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.LOW);
    }

    // ---- 多规则同时命中 → HIGH 覆盖 MEDIUM ----

    @Test
    void shouldTakeHighestRiskWhenMultipleRulesMatch() throws Exception {
        // usageRatio=95%, usable=5GB → both R1 and R3 fire
        when(provider.sample()).thenReturn(new DiskMetrics("/data", 100L * GB, 5L * GB));
        mockNoRows();

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.HIGH);
    }

    // ---- detail 结构验证 ----

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeMetricsInDetail() throws Exception {
        when(provider.sample()).thenReturn(new DiskMetrics("/data", 100L * GB, 50L * GB));
        mockIoRows(new Object[][]{{"mydb", 1000L, 100L, 5000L, 2000L, 10L, 500_000_000L}});

        ToolResult result = tool.execute(Map.of());

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        assertThat(detail).containsKeys("risk", "findings", "suggestions", "diskMetrics", "dbIoStats");

        Map<String, Object> diskMetrics = (Map<String, Object>) detail.get("diskMetrics");
        assertThat(diskMetrics).containsKeys("dataDirPath", "totalGB", "usableGB", "usedGB");
    }

    // ---- PG I/O 数据在 detail 中但不产生 finding ----

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeIoStatsInDetailButNotAsFinding() throws Exception {
        when(provider.sample()).thenReturn(new DiskMetrics("/data", 100L * GB, 50L * GB));
        mockIoRows(new Object[][]{{"mydb", 1000L, 100L, 5000L, 2000L, 10L, 500_000_000L}});

        ToolResult result = tool.execute(Map.of());

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        List<Map<String, Object>> ioList = (List<Map<String, Object>>) detail.get("dbIoStats");
        assertThat(ioList).hasSize(1);
        assertThat(ioList.get(0).get("database")).isEqualTo("mydb");
        assertThat(ioList.get(0).get("blkReadTimeMs")).isEqualTo(5000L);

        List<Map<String, String>> findings = (List<Map<String, String>>) detail.get("findings");
        assertThat(findings).noneMatch(f -> f.get("nodeType").equals("IoOverhead"));
    }

    // ---- Provider 异常 → failure ----

    @Test
    void shouldReturnFailureOnProviderException() throws Exception {
        when(provider.sample()).thenThrow(new RuntimeException("FileStore 不可用"));
        mockNoRows();

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("FileStore 不可用");
    }

    // ---- PG 查询异常降级 ----

    @Test
    void shouldSucceedEvenWhenPgQueryFails() throws Exception {
        when(provider.sample()).thenReturn(new DiskMetrics("/data", 100L * GB, 22L * GB));
        when(connection.prepareStatement(anyString())).thenThrow(new java.sql.SQLException("PG 不可用"));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.MEDIUM);
    }

    // ---- 自定义阈值 ----

    @Test
    void shouldUseCustomThresholds() throws Exception {
        DiskProperties customProps = new DiskProperties();
        customProps.setThresholdUsageHigh(0.50);
        DiskUsageTool customTool = new DiskUsageTool(provider, customProps, dataSource);

        when(provider.sample()).thenReturn(new DiskMetrics("/data", 100L * GB, 45L * GB));
        mockNoRows();

        ToolResult result = customTool.execute(Map.of());

        assertThat(result.getRisk()).isEqualTo(RiskLevel.HIGH);
    }

    // ---- helper ----

    private static final long GB = 1024L * 1024 * 1024;

    private void mockNoRows() throws Exception {
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.next()).thenReturn(false);
        when(stmt.executeQuery()).thenReturn(rs);
    }

    private ResultSet dbRs;

    private void mockIoRows(Object[][] rows) throws Exception {
        dbRs = org.mockito.Mockito.mock(ResultSet.class);
        if (rows.length > 0) {
            Boolean[] nextFlags = new Boolean[rows.length + 1];
            for (int i = 0; i < rows.length; i++) nextFlags[i] = true;
            nextFlags[rows.length] = false;
            when(dbRs.next()).thenReturn(nextFlags[0],
                    java.util.Arrays.copyOfRange(nextFlags, 1, nextFlags.length));

            String[] names = new String[rows.length];
            Long[] hits = new Long[rows.length];
            Long[] reads = new Long[rows.length];
            Long[] readTimes = new Long[rows.length];
            Long[] writeTimes = new Long[rows.length];
            Long[] tempFiles = new Long[rows.length];
            Long[] tempBytes = new Long[rows.length];
            for (int i = 0; i < rows.length; i++) {
                names[i] = (String) rows[i][0];
                hits[i] = (Long) rows[i][1];
                reads[i] = (Long) rows[i][2];
                readTimes[i] = (Long) rows[i][3];
                writeTimes[i] = (Long) rows[i][4];
                tempFiles[i] = (Long) rows[i][5];
                tempBytes[i] = (Long) rows[i][6];
            }
            when(dbRs.getString("datname")).thenReturn(names[0],
                    java.util.Arrays.copyOfRange(names, 1, names.length));
            when(dbRs.getLong("blks_hit")).thenReturn(hits[0],
                    java.util.Arrays.copyOfRange(hits, 1, hits.length));
            when(dbRs.getLong("blks_read")).thenReturn(reads[0],
                    java.util.Arrays.copyOfRange(reads, 1, reads.length));
            when(dbRs.getLong("blk_read_time")).thenReturn(readTimes[0],
                    java.util.Arrays.copyOfRange(readTimes, 1, readTimes.length));
            when(dbRs.getLong("blk_write_time")).thenReturn(writeTimes[0],
                    java.util.Arrays.copyOfRange(writeTimes, 1, writeTimes.length));
            when(dbRs.getLong("temp_files")).thenReturn(tempFiles[0],
                    java.util.Arrays.copyOfRange(tempFiles, 1, tempFiles.length));
            when(dbRs.getLong("temp_bytes")).thenReturn(tempBytes[0],
                    java.util.Arrays.copyOfRange(tempBytes, 1, tempBytes.length));
        } else {
            when(dbRs.next()).thenReturn(false);
        }
        when(stmt.executeQuery()).thenReturn(dbRs);
    }
}
