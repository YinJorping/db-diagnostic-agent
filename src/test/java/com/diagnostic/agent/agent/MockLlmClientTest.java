package com.diagnostic.agent.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockLlmClientTest {

    private final LlmClient client = new MockLlmClient();

    // ---- SQL 领域 ----

    @Test
    void shouldDetectFullTableScan() {
        String result = client.chat("你是SQL数据库性能优化专家",
                "ExplainTool 返回：type=ALL 全表扫描，rows=100000");

        assertThat(result).contains("全表扫描");
        assertThat(result).contains("索引");
    }

    @Test
    void shouldDetectFilesort() {
        String result = client.chat("你是SQL数据库性能优化专家",
                "ExplainTool 返回：Using filesort 文件排序");

        assertThat(result).contains("文件排序");
        assertThat(result).contains("ORDER BY");
    }

    @Test
    void shouldDetectSlowQuery() {
        String result = client.chat("你是SQL数据库性能优化专家",
                "SlowQueryTool 返回：top 5 慢查询, slow");

        assertThat(result).contains("慢查询");
    }

    @Test
    void shouldReturnSqlDefaultForNoIssue() {
        String result = client.chat("你是SQL数据库性能优化专家",
                "ExplainTool 返回：type=ref, rows=2, 索引正常");

        assertThat(result).contains("未发现明显");
    }

    // ---- CPU 领域 ----

    @Test
    void shouldDetectHighCpu() {
        String result = client.chat("你是系统性能优化专家，负责CPU诊断",
                "CpuUsageTool 返回：system CPU usage=92% high 饱和");

        assertThat(result).contains("CPU");
    }

    @Test
    void shouldReturnCpuNormalForNoIssue() {
        String result = client.chat("你是系统性能优化专家，负责CPU诊断",
                "CpuUsageTool 返回：CPU 资源使用正常");

        assertThat(result).contains("正常");
    }

    // ---- Memory 领域 ----

    @Test
    void shouldDetectLowBufferHitRatio() {
        String result = client.chat("你是数据库内存优化专家",
                "MemoryUsageTool 返回：缓存命中率 严重过低 HIGH");

        assertThat(result).contains("shared_buffers");
        assertThat(result).contains("缓存命中率");
    }

    // ---- JVM 领域 ----

    @Test
    void shouldDetectHighHeapUsage() {
        String result = client.chat("你是JVM性能调优专家",
                "JvmUsageTool 返回：堆内存 接近耗尽 HIGH");

        assertThat(result).contains("堆内存");
        assertThat(result).contains("-Xmx");
    }

    // ---- Disk 领域 ----

    @Test
    void shouldDetectHighDiskUsage() {
        String result = client.chat("你是数据库存储优化专家",
                "DiskUsageTool 返回：磁盘使用率 严重过高 HIGH");

        assertThat(result).contains("磁盘");
        assertThat(result).contains("扩容");
    }

    // ---- Generic 兜底 ----

    @Test
    void shouldReturnGenericForUnknownSystemPrompt() {
        String result = client.chat("你是一个助手",
                "一些文本");

        assertThat(result).contains("未发现明显");
    }
}
