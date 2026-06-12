package com.diagnostic.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class DiskUsageToolIT {

    @Autowired
    private DiskUsageTool diskUsageTool;

    @Test
    @SuppressWarnings("unchecked")
    void shouldExecuteSuccessfullyWithRealFileStore() {
        ToolResult result = diskUsageTool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getToolName()).isEqualTo("DiskUsageTool");
        assertThat(result.getRisk()).isNotNull();

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        assertThat(detail).containsKeys("risk", "findings", "suggestions", "diskMetrics", "dbIoStats");

        Map<String, Object> diskMetrics = (Map<String, Object>) detail.get("diskMetrics");
        assertThat(diskMetrics.get("totalGB")).isNotNull();
        assertThat((Long) diskMetrics.get("totalGB")).isPositive();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeDbIoStatsInDetail() {
        ToolResult result = diskUsageTool.execute(Map.of());

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        List<Map<String, Object>> ioList = (List<Map<String, Object>>) detail.get("dbIoStats");
        assertThat(ioList).isNotNull();
    }
}
