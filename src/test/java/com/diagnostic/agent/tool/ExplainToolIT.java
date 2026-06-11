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
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@SuppressWarnings("unchecked")
class ExplainToolIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("dbdiagnostic");

    @Autowired
    private ExplainTool explainTool;

    @Autowired
    private ToolRegistry toolRegistry;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(dataSource);
        // 无索引大表（用于全表扫描测试）
        jdbc.execute("DROP TABLE IF EXISTS orders_no_idx CASCADE");
        jdbc.execute("CREATE TABLE orders_no_idx (id SERIAL, status VARCHAR(20), amount NUMERIC)");
        jdbc.execute("INSERT INTO orders_no_idx (status) SELECT 'pending' FROM generate_series(1, 50000)");
        jdbc.execute("ANALYZE orders_no_idx");

        // 有主键表（用于索引命中测试）
        jdbc.execute("DROP TABLE IF EXISTS orders_pk CASCADE");
        jdbc.execute("CREATE TABLE orders_pk (id SERIAL PRIMARY KEY, status VARCHAR(20))");
        jdbc.execute("INSERT INTO orders_pk (status) SELECT 'done' FROM generate_series(1, 100)");

        // 中等规模无排序索引表（用于排序测试）
        jdbc.execute("DROP TABLE IF EXISTS logs CASCADE");
        jdbc.execute("CREATE TABLE logs (msg TEXT, created_at TIMESTAMPTZ DEFAULT NOW())");
        jdbc.execute("INSERT INTO logs (created_at) SELECT NOW() - (i || ' hours')::INTERVAL FROM generate_series(1, 20000) AS i");
    }

    // ==================== Scenario 1: 全表扫描 → HIGH ====================

    @Test
    void shouldDetectFullTableScanAsHighRisk() {
        ToolResult result = explainTool.execute(Map.of(
                "sql", "SELECT * FROM orders_no_idx WHERE status = 'pending'"));

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        assertThat(detail.get("risk")).isEqualTo("HIGH");
        assertThat((List<?>) detail.get("findings")).isNotEmpty();
        assertThat(result.getSummary()).contains("HIGH");
    }

    // ==================== Scenario 2: 索引命中 → LOW ====================

    @Test
    void shouldDetectIndexScanAsLowRisk() {
        ToolResult result = explainTool.execute(Map.of(
                "sql", "SELECT * FROM orders_pk WHERE id = 1"));

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        assertThat(detail.get("risk")).isEqualTo("LOW");
        assertThat((List<?>) detail.get("findings")).isEmpty();
    }

    // ==================== Scenario 3: 排序 → MEDIUM ====================

    @Test
    void shouldDetectUnindexedSortAsMediumRisk() {
        ToolResult result = explainTool.execute(Map.of(
                "sql", "SELECT * FROM logs ORDER BY created_at DESC"));

        assertThat(result.isSuccess()).isTrue();
        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        assertThat(detail.get("risk")).isIn("MEDIUM", "HIGH");
        // 如果 Seq Scan 主导，也可能触发 HIGH；核心是成功检测到问题
    }

    // ==================== Scenario 4: 非 SELECT 被拒绝 ====================

    @Test
    void shouldRejectNonSelectStatement() {
        ToolResult result = explainTool.execute(Map.of(
                "sql", "DELETE FROM orders_no_idx"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("SELECT");
    }

    // ==================== Scenario 5: 空 SQL 被拒绝 ====================

    @Test
    void shouldRejectEmptySql() {
        ToolResult result = explainTool.execute(Map.of("sql", ""));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("不能为空");
    }

    // ==================== Scenario 6: 多语句被拒绝 ====================

    @Test
    void shouldRejectMultiStatement() {
        ToolResult result = explainTool.execute(Map.of(
                "sql", "SELECT 1; DELETE FROM orders_no_idx;"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).contains("多语句");
    }

    // ==================== Scenario 7: 超时 ====================

    @Test
    void shouldTimeoutOnLongQuery() throws Exception {
        // EXPLAIN 不执行函数，pg_sleep 不会生效。
        // 通过锁表让 EXPLAIN 阻塞，触发 JDBC setQueryTimeout(5s)。
        try (Connection locker = dataSource.getConnection()) {
            locker.setAutoCommit(false);
            locker.createStatement().execute("LOCK TABLE orders_no_idx IN ACCESS EXCLUSIVE MODE");

            long start = System.currentTimeMillis();
            ToolResult result = explainTool.execute(Map.of(
                    "sql", "SELECT * FROM orders_no_idx"));

            long elapsed = System.currentTimeMillis() - start;

            locker.rollback();

            assertThat(result.isSuccess()).isFalse();
            assertThat(elapsed).isLessThan(8000); // 在 8s 内返回（5s 超时 + 容忍度）
        }
    }

    // ==================== Scenario 8: ToolRegistry 自动发现 ====================

    @Test
    void explainToolShouldBeRegistered() {
        assertThat(explainTool.getName()).isEqualTo("ExplainTool");
    }

    @Test
    void toolRegistryShouldDiscoverExplainToolViaSpring() {
        assertThat(toolRegistry.get("ExplainTool")).isPresent();
        assertThat(toolRegistry.get("ExplainTool").get()).isInstanceOf(ExplainTool.class);
    }

    @Test
    void suggestionsShouldBeDeduplicated() {
        // insert 50000 rows with same status to test dedup
        jdbc.execute("DROP TABLE IF EXISTS dedup_test CASCADE");
        jdbc.execute("CREATE TABLE dedup_test (id SERIAL, status VARCHAR(20), amount NUMERIC)");
        jdbc.execute("INSERT INTO dedup_test (status) SELECT 'pending' FROM generate_series(1, 50000)");
        jdbc.execute("ANALYZE dedup_test");

        ToolResult result = explainTool.execute(Map.of(
                "sql", "SELECT * FROM dedup_test WHERE status = 'pending' ORDER BY status"));

        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) result.getDetail();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> suggestions = (List<Map<String, String>>) detail.get("suggestions");

        // 按 action 去重：同一个 status 字段只出现一次建议
        List<String> actions = suggestions.stream().map(s -> s.get("action")).toList();
        assertThat(actions).doesNotHaveDuplicates();
    }
}
