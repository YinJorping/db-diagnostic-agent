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
class MemoryUsageToolTest {

    @Mock private DataSource dataSource;
    @Mock private Connection connection;
    @Mock private PreparedStatement stmt;

    private MemoryUsageTool tool;

    @BeforeEach
    void setup() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(stmt);
        tool = new MemoryUsageTool(dataSource, new MemoryProperties());
    }

    // ---- 工具基本属性 ----

    @Test
    void shouldReturnCorrectToolName() {
        assertThat(tool.getName()).isEqualTo("MemoryUsageTool");
    }

    @Test
    void shouldReturnNonEmptyDescription() {
        assertThat(tool.getDescription()).isNotBlank();
    }

    // ---- 单位解析 ----

    @Test
    void shouldParseKBUnit() {
        assertThat(MemoryUsageTool.parseToBytes("4096", "kB")).isEqualTo(4096L * 1024);
    }

    @Test
    void shouldParseMBUnit() {
        assertThat(MemoryUsageTool.parseToBytes("128", "MB")).isEqualTo(128L * 1024 * 1024);
    }

    @Test
    void shouldParse8KBUnit() {
        assertThat(MemoryUsageTool.parseToBytes("1000", "8kB")).isEqualTo(1000L * 8 * 1024);
    }

    @Test
    void shouldParseNullUnitAsBytes() {
        assertThat(MemoryUsageTool.parseToBytes("512", null)).isEqualTo(512L);
    }

    @Test
    void shouldReturnZeroForUnparseableValue() {
        assertThat(MemoryUsageTool.parseToBytes("abc", "kB")).isZero();
    }

    // ---- R1: bufferHitRatio < 99% → HIGH ----

    @Test
    @SuppressWarnings("unchecked")
    void shouldDetectLowBufferHitRatio() throws Exception {
        mockDbStatRow("mydb", 90, 10, 0, 0, false);
        mockSettingsRows(new Object[][]{});

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        List<Map<String, String>> findings = (List<Map<String, String>>) detail.get("findings");
        assertThat(findings).anyMatch(f -> "HIGH".equals(f.get("level"))
                && f.get("nodeType").equals("BufferHitRatio"));
    }

    // ---- R2: bufferHitRatio < 95% → MEDIUM ----

    @Test
    @SuppressWarnings("unchecked")
    void shouldDetectMediumBufferHitRatio() throws Exception {
        mockDbStatRow("mydb", 96, 4, 0, 0, false);
        mockSettingsRows(new Object[][]{});

        ToolResult result = tool.execute(Map.of());

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        List<Map<String, String>> findings = (List<Map<String, String>>) detail.get("findings");
        assertThat(findings).anyMatch(f -> "MEDIUM".equals(f.get("level"))
                && f.get("nodeType").equals("BufferHitRatio"));
    }

    // ---- R3: temp_files > 100 → MEDIUM ----

    @Test
    @SuppressWarnings("unchecked")
    void shouldDetectExcessiveTempFiles() throws Exception {
        mockDbStatRow("mydb", 99, 1, 200, 500_000_000, false);
        mockSettingsRows(new Object[][]{});

        ToolResult result = tool.execute(Map.of());

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        List<Map<String, String>> findings = (List<Map<String, String>>) detail.get("findings");
        assertThat(findings).anyMatch(f -> f.get("nodeType").equals("TempFiles"));
    }

    // ---- R4: shared_buffers < 256MB → MEDIUM ----

    @Test
    @SuppressWarnings("unchecked")
    void shouldDetectLowSharedBuffers() throws Exception {
        mockDbStatRow("mydb", 99, 1, 0, 0, false);
        mockSettingsRows(new Object[][]{{"shared_buffers", "131072", "kB"}}); // 128MB

        ToolResult result = tool.execute(Map.of());

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        List<Map<String, String>> findings = (List<Map<String, String>>) detail.get("findings");
        assertThat(findings).anyMatch(f -> f.get("nodeType").equals("SharedBuffers"));
    }

    // ---- R5: work_mem > 256MB → MEDIUM ----

    @Test
    @SuppressWarnings("unchecked")
    void shouldDetectHighWorkMem() throws Exception {
        mockDbStatRow("mydb", 99, 1, 0, 0, false);
        mockSettingsRows(new Object[][]{{"work_mem", "524288", "kB"}}); // 512MB

        ToolResult result = tool.execute(Map.of());

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        List<Map<String, String>> findings = (List<Map<String, String>>) detail.get("findings");
        assertThat(findings).anyMatch(f -> f.get("nodeType").equals("WorkMem"));
    }

    // ---- 正常场景 ----

    @Test
    void shouldReturnLowRiskWhenAllMetricsAreHealthy() throws Exception {
        mockDbStatRow("mydb", 999, 1, 0, 0, false);
        mockSettingsRows(new Object[][]{
                {"shared_buffers", "524288", "kB"}, // 512MB
                {"work_mem", "4096", "kB"}           // 4MB
        });

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.LOW);
        assertThat(result.getSummary()).contains("LOW");
    }

    // ---- 多规则同时命中 → HIGH 覆盖 MEDIUM ----

    @Test
    @SuppressWarnings("unchecked")
    void shouldTakeHighestRiskWhenMultipleRulesMatch() throws Exception {
        mockDbStatRow("mydb", 80, 20, 300, 2_000_000_000L, false);
        mockSettingsRows(new Object[][]{{"shared_buffers", "131072", "kB"}});

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.HIGH);
    }

    // ---- 除零保护 ----

    @Test
    void shouldSkipDatabaseWithZeroBlocks() throws Exception {
        mockDbStatRow("mydb", 0, 0, 0, 0, false); // blks_hit = 0, blks_read = 0
        mockSettingsRows(new Object[][]{});

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isEqualTo(RiskLevel.LOW);
    }

    // ---- detail 结构验证 ----

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeMetricsInDetail() throws Exception {
        mockDbStatRow("mydb", 99, 1, 0, 0, false);
        mockSettingsRows(new Object[][]{{"shared_buffers", "262144", "kB"}});

        ToolResult result = tool.execute(Map.of());

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        assertThat(detail).containsKeys("risk", "findings", "suggestions", "databases", "settings");
    }

    // ---- DataSource 异常 ----

    @Test
    void shouldReturnFailureOnDataSourceException() throws Exception {
        when(dataSource.getConnection()).thenThrow(new RuntimeException("数据库连接失败"));

        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("数据库连接失败");
    }

    // ---- 自定义阈值 ----

    @Test
    @SuppressWarnings("unchecked")
    void shouldUseCustomThresholds() throws Exception {
        MemoryProperties customProps = new MemoryProperties();
        customProps.setThresholdBufferHitHigh(0.80);
        MemoryUsageTool customTool = new MemoryUsageTool(dataSource, customProps);

        mockDbStatRow("mydb", 70, 30, 0, 0, false); // 70% — 低于 0.80 的阈值
        mockSettingsRows(new Object[][]{});

        ToolResult result = customTool.execute(Map.of());

        assertThat(result.getRisk()).isEqualTo(RiskLevel.HIGH);
    }

    // ---- 多个数据库 ----

    @Test
    @SuppressWarnings("unchecked")
    void shouldAnalyzeMultipleDatabases() throws Exception {
        mockMultiDbStatRows(new Object[][]{
                {"db1", 99L, 1L, 0L, 0L},
                {"db2", 80L, 20L, 0L, 0L},
        });
        mockSettingsRows(new Object[][]{});

        ToolResult result = tool.execute(Map.of());

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        List<Map<String, Object>> dbs = (List<Map<String, Object>>) detail.get("databases");
        assertThat(dbs).hasSize(2);
    }

    // ---- 辅助 mock 方法 ----

    private ResultSet dbRs;

    private void mockDbStatRow(String datname, long blksHit, long blksRead,
                               long tempFiles, long tempBytes, boolean hasNext) throws Exception {
        dbRs = org.mockito.Mockito.mock(ResultSet.class);
        when(dbRs.next()).thenReturn(true, hasNext);
        when(dbRs.getString("datname")).thenReturn(datname);
        when(dbRs.getLong("blks_hit")).thenReturn(blksHit);
        when(dbRs.getLong("blks_read")).thenReturn(blksRead);
        when(dbRs.getLong("temp_files")).thenReturn(tempFiles);
        when(dbRs.getLong("temp_bytes")).thenReturn(tempBytes);
    }

    private void mockSettingsRows(Object[][] rows) throws Exception {
        ResultSet settingsRs = org.mockito.Mockito.mock(ResultSet.class);
        if (rows.length > 0) {
            Boolean[] nextFlags = new Boolean[rows.length + 1];
            for (int i = 0; i < rows.length; i++) nextFlags[i] = true;
            nextFlags[rows.length] = false;
            when(settingsRs.next()).thenReturn(nextFlags[0], java.util.Arrays.copyOfRange(nextFlags, 1, nextFlags.length));

            String[] names = new String[rows.length];
            String[] settings = new String[rows.length];
            String[] units = new String[rows.length];
            for (int i = 0; i < rows.length; i++) {
                names[i] = (String) rows[i][0];
                settings[i] = (String) rows[i][1];
                units[i] = (String) rows[i][2];
            }
            when(settingsRs.getString("name")).thenReturn(names[0], java.util.Arrays.copyOfRange(names, 1, names.length));
            when(settingsRs.getString("setting")).thenReturn(settings[0], java.util.Arrays.copyOfRange(settings, 1, settings.length));
            when(settingsRs.getString("unit")).thenReturn(units[0], java.util.Arrays.copyOfRange(units, 1, units.length));
        } else {
            when(settingsRs.next()).thenReturn(false);
        }
        when(stmt.executeQuery()).thenReturn(dbRs, settingsRs);
    }

    private void mockMultiDbStatRows(Object[][] rows) throws Exception {
        dbRs = org.mockito.Mockito.mock(ResultSet.class);
        ResultSet settingsRs = org.mockito.Mockito.mock(ResultSet.class);

        when(dbRs.next()).thenReturn(true, true, false);

        String[] names = new String[rows.length];
        Long[] hits = new Long[rows.length];
        Long[] reads = new Long[rows.length];
        Long[] files = new Long[rows.length];
        Long[] bytesVals = new Long[rows.length];
        for (int i = 0; i < rows.length; i++) {
            names[i] = (String) rows[i][0];
            hits[i] = (Long) rows[i][1];
            reads[i] = (Long) rows[i][2];
            files[i] = (Long) rows[i][3];
            bytesVals[i] = (Long) rows[i][4];
        }
        when(dbRs.getString("datname")).thenReturn(names[0], names[1]);
        when(dbRs.getLong("blks_hit")).thenReturn(hits[0], hits[1]);
        when(dbRs.getLong("blks_read")).thenReturn(reads[0], reads[1]);
        when(dbRs.getLong("temp_files")).thenReturn(files[0], files[1]);
        when(dbRs.getLong("temp_bytes")).thenReturn(bytesVals[0], bytesVals[1]);

        when(settingsRs.next()).thenReturn(false);
        when(stmt.executeQuery()).thenReturn(dbRs).thenReturn(settingsRs);
    }
}
