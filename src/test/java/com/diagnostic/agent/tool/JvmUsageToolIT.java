package com.diagnostic.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class JvmUsageToolIT {

    @Autowired
    private JvmUsageTool jvmUsageTool;

    @Test
    @SuppressWarnings("unchecked")
    void shouldExecuteSuccessfullyWithRealJmx() {
        ToolResult result = jvmUsageTool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getToolName()).isEqualTo("JvmUsageTool");
        assertThat(result.getRisk()).isNotNull();

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        assertThat(detail).containsKeys("risk", "findings", "suggestions", "metrics", "gcSnapshots");

        Map<String, Object> metrics = (Map<String, Object>) detail.get("metrics");
        assertThat(metrics).containsKeys("heapUsedMB", "heapMaxMB", "threadCount", "uptimeMinutes");
        assertThat((Integer) metrics.get("threadCount")).isPositive();
        assertThat((Long) metrics.get("uptimeMinutes")).isPositive();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeGcSnapshotsInDetail() {
        ToolResult result = jvmUsageTool.execute(Map.of());

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        var gcSnapshots = (java.util.List<Map<String, Object>>) detail.get("gcSnapshots");
        assertThat(gcSnapshots).isNotEmpty();
        assertThat(gcSnapshots.get(0)).containsKeys("name", "collectionCount", "collectionTimeMs");
    }
}
