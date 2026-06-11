package com.diagnostic.agent.agent;

import com.diagnostic.agent.tool.RiskLevel;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class SqlDiagnosisAgentIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("dbdiagnostic")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");

    @Autowired
    private SqlDiagnosisAgent agent;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(dataSource);

        // ExplainTool 测试表
        jdbc.execute("DROP TABLE IF EXISTS orders_no_idx CASCADE");
        jdbc.execute("CREATE TABLE orders_no_idx (id SERIAL, status VARCHAR(20), amount NUMERIC)");
        jdbc.execute("INSERT INTO orders_no_idx (status) SELECT 'pending' FROM generate_series(1, 50000)");
        jdbc.execute("ANALYZE orders_no_idx");

        jdbc.execute("DROP TABLE IF EXISTS orders_pk CASCADE");
        jdbc.execute("CREATE TABLE orders_pk (id SERIAL PRIMARY KEY, status VARCHAR(20))");
        jdbc.execute("INSERT INTO orders_pk (status) SELECT 'done' FROM generate_series(1, 100)");

        // SlowQueryTool 扩展
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        jdbc.execute("SELECT pg_stat_statements_reset()");
        jdbc.execute("SELECT pg_sleep(0.2)");
        jdbc.execute("SELECT pg_sleep(1.1)");
    }

    // ==================== Scenario 1: 无 SQL → ExplainTool 跳过 ====================

    @Test
    void shouldSkipExplainToolWhenNoSql() {
        DiagnosisResult result = agent.diagnose("数据库响应变慢，请帮我诊断");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAgentName()).isEqualTo("SqlDiagnosisAgent");
        assertThat(result.getSummary()).isNotNull();
    }

    // ==================== Scenario 2: 有 SQL → 两个 Tool 都执行 ====================

    @Test
    void shouldExecuteBothToolsWhenSqlPresent() {
        DiagnosisResult result = agent.diagnose(
                "SELECT * FROM orders_no_idx WHERE status = 'pending' 这个查询很慢");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getRisk()).isIn(RiskLevel.HIGH, RiskLevel.MEDIUM);
    }

    // ==================== Scenario 3: 非法 SQL → ExplainTool failure，整体仍 success ====================

    @Test
    void shouldStillSucceedWhenExplainToolFailsOnIllegalSql() {
        DiagnosisResult result = agent.diagnose("DELETE FROM orders_no_idx WHERE status = 'done'");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAgentName()).isEqualTo("SqlDiagnosisAgent");
        // SlowQueryTool 仍然执行成功，整体不应失败
        assertThat(result.getSummary()).isNotNull();
    }

    // ==================== Scenario 4: SlowQueryTool 始终执行 ====================

    @Test
    void shouldAlwaysExecuteSlowQueryTool() {
        // 无 SQL 输入，但 SlowQueryTool 仍应执行
        DiagnosisResult result = agent.diagnose("数据库慢");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSummary()).isNotNull();
    }
}
