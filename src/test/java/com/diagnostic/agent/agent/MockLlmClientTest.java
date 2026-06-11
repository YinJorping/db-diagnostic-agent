package com.diagnostic.agent.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MockLlmClientTest {

    private final LlmClient client = new MockLlmClient();

    @Test
    void shouldDetectFullTableScan() {
        String result = client.chat("你是数据库专家",
                "ExplainTool 返回：type=ALL 全表扫描，rows=100000");

        assertThat(result).contains("全表扫描");
        assertThat(result).contains("索引");
    }

    @Test
    void shouldDetectFilesort() {
        String result = client.chat("你是数据库专家",
                "ExplainTool 返回：Using filesort 文件排序");

        assertThat(result).contains("filesort");
        assertThat(result).contains("ORDER BY");
    }

    @Test
    void shouldDetectSlowQuery() {
        String result = client.chat("你是数据库专家",
                "SlowQueryTool 返回：top 5 慢查询");

        assertThat(result).contains("慢查询");
    }

    @Test
    void shouldDetectHighCpu() {
        String result = client.chat("你是数据库专家",
                "CpuLoadTool 返回：CPU usage=92% high");

        assertThat(result).contains("CPU");
    }

    @Test
    void shouldReturnDefaultForNoIssue() {
        String result = client.chat("你是数据库专家",
                "ExplainTool 返回：type=ref, rows=2, 索引正常");

        assertThat(result).contains("未发现明显");
    }
}
