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
class SlowQueryToolIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("dbdiagnostic")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");

    @Autowired
    private SlowQueryTool slowQueryTool;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        jdbc.execute("SELECT pg_stat_statements_reset()");

        // 使用 pg_sleep 生成慢查询记录，确保跨越风险阈值
        jdbc.execute("SELECT pg_sleep(0.2)");   // ~200ms → MEDIUM (>100ms)
        jdbc.execute("SELECT pg_sleep(1.1)");   // ~1100ms → HIGH (>1000ms)
    }

    // ==================== Scenario 1: 正常查询 ====================

    @Test
    void shouldReturnSlowQueries() {
        ToolResult result = slowQueryTool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        assertThat(detail.get("risk")).isIn("MEDIUM", "HIGH");
        assertThat((List<?>) detail.get("findings")).isNotEmpty();
        assertThat(result.getSummary()).contains("慢查询");
    }

    @Test
    void shouldRespectLimitParam() {
        ToolResult result = slowQueryTool.execute(Map.of("limit", 1));

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        List<Map<String, Object>> queries = (List<Map<String, Object>>) detail.get("slowQueries");
        assertThat(queries).hasSizeLessThanOrEqualTo(1);
    }

    // ==================== Scenario 2: 空结果 ====================

    @Test
    void shouldReturnLowRiskWhenNoSlowQueries() {
        jdbc.execute("SELECT pg_stat_statements_reset()");

        ToolResult result = slowQueryTool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        assertThat(detail.get("risk")).isEqualTo("LOW");
        assertThat((List<?>) detail.get("findings")).isEmpty();
        assertThat(result.getSummary()).contains("未发现慢查询");
    }

    // ==================== Scenario 3: 参数非法 ====================

    @Test
    void shouldRejectLimitTooLarge() {
        ToolResult result = slowQueryTool.execute(Map.of("limit", 999));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("1-100");
    }

    @Test
    void shouldRejectNegativeLimit() {
        ToolResult result = slowQueryTool.execute(Map.of("limit", -1));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("1-100");
    }

    @Test
    void shouldRejectNonNumericLimit() {
        ToolResult result = slowQueryTool.execute(Map.of("limit", "abc"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("1-100");
    }

    @Test
    void shouldUseDefaultLimitWhenNotProvided() {
        ToolResult result = slowQueryTool.execute(Map.of());

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        List<Map<String, Object>> queries = (List<Map<String, Object>>) detail.get("slowQueries");
        // 默认 limit=10，结果应 <= 10
        assertThat(queries).hasSizeLessThanOrEqualTo(10);
    }

    // ==================== Scenario 4: 扩展不存在 ====================

    @Test
    void shouldHandleMissingExtension() {
        jdbc.execute("DROP EXTENSION pg_stat_statements");

        ToolResult result = slowQueryTool.execute(Map.of());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("pg_stat_statements");

        // 还原扩展，避免影响其他测试
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
    }

    // ==================== Scenario 5: 超时 ====================

    @Test
    void shouldTimeoutOnLongQuery() {
        // 通过覆写 buildQuerySql 注入慢查询来验证 setQueryTimeout(5s) 生效
        SlowQueryTool timeoutVariant = new SlowQueryTool(dataSource) {
            @Override
            String buildQuerySql(int limit) {
                return "SELECT pg_sleep(10)";
            }
        };

        long start = System.currentTimeMillis();
        ToolResult result = timeoutVariant.execute(Map.of());

        long elapsed = System.currentTimeMillis() - start;
        assertThat(result.isSuccess()).isFalse();
        assertThat(elapsed).isLessThan(8000); // 5s 超时 + 容忍度
    }

    // ==================== Scenario 6: ToolRegistry 自动发现 ====================

    @Test
    void slowQueryToolShouldBeRegistered() {
        assertThat(slowQueryTool.getName()).isEqualTo("SlowQueryTool");
    }

    @Test
    void toolRegistryShouldDiscoverSlowQueryToolViaSpring() {
        assertThat(toolRegistry.get("SlowQueryTool")).isPresent();
        assertThat(toolRegistry.get("SlowQueryTool").get()).isInstanceOf(SlowQueryTool.class);
    }
}
