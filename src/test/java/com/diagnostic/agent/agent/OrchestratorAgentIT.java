package com.diagnostic.agent.agent;

import com.diagnostic.agent.repository.DiagnosisRecord;
import com.diagnostic.agent.repository.DiagnosisRecordRepository;
import com.diagnostic.agent.repository.SessionRepository;
import com.diagnostic.agent.tool.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class OrchestratorAgentIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("dbdiagnostic")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");

    @Autowired
    private OrchestratorAgent orchestrator;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private DiagnosisRecordRepository recordRepository;

    @Autowired
    private DataSource dataSource;

    @SpyBean
    private SqlDiagnosisAgent sqlDiagnosisAgent;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS orders_no_idx CASCADE");
        jdbc.execute("CREATE TABLE orders_no_idx (id SERIAL, status VARCHAR(20), amount NUMERIC)");
        jdbc.execute("INSERT INTO orders_no_idx (status) SELECT 'pending' FROM generate_series(1, 50000)");
        jdbc.execute("ANALYZE orders_no_idx");
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        jdbc.execute("SELECT pg_stat_statements_reset()");
        jdbc.execute("SELECT pg_sleep(0.2)");
        jdbc.execute("SELECT pg_sleep(1.1)");
    }

    // ==================== Scenario 1: SQL 问题完整流程 ====================

    @Test
    void shouldCompleteFullFlowForSqlProblem() {
        DiagnosisReport report = orchestrator.diagnose("sess-001",
                "SELECT * FROM orders_no_idx WHERE status = 'pending' 很慢");

        assertThat(report.success()).isTrue();
        assertThat(report.agentResults()).hasSize(1);
        assertThat(report.agentResults().get(0).agentName()).isEqualTo("SqlDiagnosisAgent");

        assertThat(sessionRepository.findBySessionId("sess-001")).isPresent();

        List<DiagnosisRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc("sess-001");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo(DiagnosisStatus.COMPLETED);
        assertThat(records.get(0).getAgentName()).contains("SqlDiagnosisAgent");
    }

    // ==================== Scenario 2: 非 SQL → GENERAL_AGENT ====================

    @Test
    void shouldFallbackToGeneralAgentForNonSqlProblem() {
        DiagnosisReport report = orchestrator.diagnose("sess-002", "今天天气怎么样");

        assertThat(report.success()).isTrue();
        assertThat(report.agentResults().get(0).agentName()).isEqualTo("GeneralAgent");

        List<DiagnosisRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc("sess-002");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo(DiagnosisStatus.COMPLETED);
    }

    // ==================== Scenario 3: Session 复用 ====================

    @Test
    void shouldReuseSessionForMultipleDiagnoses() {
        orchestrator.diagnose("sess-003", "SELECT 1");
        orchestrator.diagnose("sess-003", "SELECT 2");

        assertThat(sessionRepository.findBySessionId("sess-003")).isPresent();
        List<DiagnosisRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc("sess-003");
        assertThat(records).hasSize(2);
        assertThat(records).allMatch(r -> DiagnosisStatus.COMPLETED.equals(r.getStatus()));
    }

    // ==================== Scenario 4: Agent 异常 → 并行隔离, report 标记 failure ====================

    @Test
    void shouldReturnFailedAgentResultOnException() {
        doThrow(new RuntimeException("Agent 内部错误"))
                .when(sqlDiagnosisAgent).diagnose(any(DiagnosisContext.class));

        DiagnosisReport report = orchestrator.diagnose("sess-004", "SELECT * FROM t");

        assertThat(report.success()).isFalse();
        assertThat(report.overallRisk()).isEqualTo(RiskLevel.UNKNOWN);
        assertThat(report.agentResults()).hasSize(1);
        assertThat(report.agentResults().get(0).success()).isFalse();

        List<DiagnosisRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc("sess-004");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo(DiagnosisStatus.COMPLETED);
    }

    // ==================== Scenario 5: 历史对话注入 ====================

    @Test
    void shouldIncludeHistoryInSubsequentDiagnosis() {
        orchestrator.diagnose("sess-hist-01", "数据库查询很慢，怀疑缺少索引");

        DiagnosisReport report2 = orchestrator.diagnose("sess-hist-01", "CPU突然到100%了");

        assertThat(report2.success()).isTrue();

        List<DiagnosisRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc("sess-hist-01");
        assertThat(records).hasSize(2);
        assertThat(records).allMatch(r -> DiagnosisStatus.COMPLETED.equals(r.getStatus()));

        assertThat(sessionRepository.findBySessionId("sess-hist-01")).isPresent();
    }

    // ==================== Scenario 6: 双 Agent 并行 (SQL + CPU) ====================

    @Test
    void shouldParallelDiagnoseWithBothAgents() {
        DiagnosisReport report = orchestrator.diagnose("sess-dual-01",
                "数据库查询慢且CPU飙高");

        assertThat(report.success()).isTrue();
        assertThat(report.agentResults()).hasSize(2);
        assertThat(report.agentResults().stream()
                .map(DiagnosisReport.AgentResult::agentName))
                .contains("SqlDiagnosisAgent", "CpuDiagnosisAgent");

        assertThat(sessionRepository.findBySessionId("sess-dual-01")).isPresent();

        List<DiagnosisRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc("sess-dual-01");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo(DiagnosisStatus.COMPLETED);
        assertThat(records.get(0).getAgentName()).contains("SqlDiagnosisAgent")
                .contains("CpuDiagnosisAgent");
    }

    // ==================== Scenario 7: 三 Agent 并行 (SQL + CPU + Memory) ====================

    @Test
    void shouldParallelDiagnoseWithThreeAgents() {
        DiagnosisReport report = orchestrator.diagnose("sess-triple-01",
                "数据库查询慢且CPU高且内存不足");

        assertThat(report.success()).isTrue();
        assertThat(report.agentResults()).hasSize(3);
        assertThat(report.agentResults().stream()
                .map(DiagnosisReport.AgentResult::agentName))
                .contains("SqlDiagnosisAgent", "CpuDiagnosisAgent", "MemoryDiagnosisAgent");

        assertThat(sessionRepository.findBySessionId("sess-triple-01")).isPresent();

        List<DiagnosisRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc("sess-triple-01");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo(DiagnosisStatus.COMPLETED);
        assertThat(records.get(0).getAgentName()).contains("SqlDiagnosisAgent")
                .contains("CpuDiagnosisAgent")
                .contains("MemoryDiagnosisAgent");
    }
}
