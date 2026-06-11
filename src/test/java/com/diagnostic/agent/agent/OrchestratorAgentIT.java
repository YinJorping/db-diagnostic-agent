package com.diagnostic.agent.agent;

import com.diagnostic.agent.repository.DiagnosisRecord;
import com.diagnostic.agent.repository.DiagnosisRecordRepository;
import com.diagnostic.agent.repository.SessionRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
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
        DiagnosisResult result = orchestrator.diagnose("sess-001",
                "SELECT * FROM orders_no_idx WHERE status = 'pending' 很慢");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAgentName()).isEqualTo("SqlDiagnosisAgent");

        assertThat(sessionRepository.findBySessionId("sess-001")).isPresent();

        List<DiagnosisRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc("sess-001");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo(DiagnosisStatus.COMPLETED);
        assertThat(records.get(0).getAgentName()).isEqualTo("SqlDiagnosisAgent");
    }

    // ==================== Scenario 2: 非 SQL → GENERAL_AGENT ====================

    @Test
    void shouldFallbackToGeneralAgentForNonSqlProblem() {
        DiagnosisResult result = orchestrator.diagnose("sess-002", "今天天气怎么样");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAgentName()).isEqualTo("GeneralAgent");

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

    // ==================== Scenario 4: Agent 异常 → FAILED + 传播 ====================

    @Test
    void shouldMarkRecordFailedAndRethrowOnAgentException() {
        doThrow(new RuntimeException("Agent 内部错误"))
                .when(sqlDiagnosisAgent).diagnose(anyString());

        assertThatThrownBy(() -> orchestrator.diagnose("sess-004", "SELECT * FROM t"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Agent 内部错误");

        List<DiagnosisRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc("sess-004");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo(DiagnosisStatus.FAILED);
    }
}
