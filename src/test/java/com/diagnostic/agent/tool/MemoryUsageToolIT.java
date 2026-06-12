package com.diagnostic.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@SuppressWarnings("unchecked")
class MemoryUsageToolIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("dbdiagnostic")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");

    @Autowired
    private MemoryUsageTool tool;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void shouldExecuteSuccessfullyWithRealPostgres() {
        ToolResult result = tool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getToolName()).isEqualTo("MemoryUsageTool");
        assertThat(result.getSummary()).isNotBlank();
        assertThat(result.getDetail()).isNotNull();
    }

    @Test
    void shouldIncludeDatabaseMetricsInDetail() {
        ToolResult result = tool.execute(Map.of());

        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        assertThat(detail).containsKeys("risk", "findings", "suggestions", "databases", "settings");

        List<Map<String, Object>> databases = (List<Map<String, Object>>) detail.get("databases");
        assertThat(databases).isNotEmpty();
        Map<String, Object> db = databases.get(0);
        assertThat(db).containsKeys("database", "blksHit", "blksRead", "hitRatio", "tempFiles", "tempBytes");
    }
}
