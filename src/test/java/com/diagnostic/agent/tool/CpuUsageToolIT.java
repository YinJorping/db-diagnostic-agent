package com.diagnostic.agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class CpuUsageToolIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("dbdiagnostic");

    @Autowired
    private CpuUsageTool tool;

    @Test
    void shouldExecuteSuccessfullyWithRealJmxData() {
        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getToolName()).isEqualTo("CpuUsageTool");
        assertThat(result.getSummary()).isNotBlank();
        assertThat(result.getDetail()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void shouldIncludeMetricsInDetailFromRealJmx() {
        ToolResult result = tool.execute(Map.of());

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        Map<String, Object> metrics = (Map<String, Object>) detail.get("metrics");
        assertThat(metrics.get("availableProcessors")).isNotNull();
    }
}
