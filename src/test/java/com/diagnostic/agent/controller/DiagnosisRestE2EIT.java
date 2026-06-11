package com.diagnostic.agent.controller;

import com.diagnostic.agent.agent.DiagnosisStatus;
import com.diagnostic.agent.common.ApiResponse;
import com.diagnostic.agent.controller.dto.DiagnosisResponse;
import com.diagnostic.agent.repository.DiagnosisRecord;
import com.diagnostic.agent.repository.DiagnosisRecordRepository;
import com.diagnostic.agent.repository.SessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class DiagnosisRestE2EIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("dbdiagnostic")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private DiagnosisRecordRepository recordRepository;

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void setup() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS users CASCADE");
        jdbc.execute("CREATE TABLE users (id SERIAL, name VARCHAR(50), age INT)");
        jdbc.execute("INSERT INTO users (name, age) SELECT 'user' || i, (i % 80) + 1 FROM generate_series(1, 20000) AS i");
        jdbc.execute("ANALYZE users");
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        jdbc.execute("SELECT pg_stat_statements_reset()");
        jdbc.execute("SELECT pg_sleep(0.2)");
        jdbc.execute("SELECT pg_sleep(1.1)");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private <T> ResponseEntity<ApiResponse<T>> post(String path, Object body, ParameterizedTypeReference<ApiResponse<T>> type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url(path), HttpMethod.POST,
                new HttpEntity<>(body, headers), type);
    }

    private static final ParameterizedTypeReference<ApiResponse<DiagnosisResponse>> DIAG_RESPONSE =
            new ParameterizedTypeReference<>() {};

    // ==================== Scenario 1: SQL 完整链路 ====================

    @Test
    @SuppressWarnings("unchecked")
    void shouldDiagnoseSqlEndToEnd() {
        Map<String, String> request = Map.of(
                "sessionId", "e2e-sql-001",
                "problem", "SELECT * FROM users WHERE age > 18");

        ResponseEntity<ApiResponse<DiagnosisResponse>> resp =
                post("/api/diagnose", request, DIAG_RESPONSE);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ApiResponse<DiagnosisResponse> body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCode()).isEqualTo(0);
        assertThat(body.getData()).isNotNull();
        assertThat(body.getData().agentName()).isNotNull();
        assertThat(body.getData().risk()).isNotNull();

        // Session + Record 落库
        assertThat(sessionRepository.findBySessionId("e2e-sql-001")).isPresent();
        List<DiagnosisRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc("e2e-sql-001");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo(DiagnosisStatus.COMPLETED);
    }

    // ==================== Scenario 2: 非 SQL fallback ====================

    @Test
    void shouldFallbackToGeneralAgentForNonSql() {
        Map<String, String> request = Map.of(
                "sessionId", "e2e-general",
                "problem", "今天天气怎么样");

        ResponseEntity<ApiResponse<DiagnosisResponse>> resp =
                post("/api/diagnose", request, DIAG_RESPONSE);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);
        assertThat(resp.getBody().getData().agentName()).isEqualTo("GeneralAgent");

        List<DiagnosisRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc("e2e-general");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo(DiagnosisStatus.COMPLETED);
    }

    // ==================== Scenario 3: 参数校验失败 ====================

    @Test
    void shouldReturnValidationError() {
        Map<String, String> request = Map.of(
                "sessionId", "",
                "problem", "SELECT 1");

        ResponseEntity<ApiResponse<DiagnosisResponse>> resp =
                post("/api/diagnose", request, DIAG_RESPONSE);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(4000);
    }

    // ==================== Scenario 4: Session 复用 ====================

    @Test
    void shouldReuseSessionAcrossMultipleDiagnoses() {
        Map<String, String> req1 = Map.of("sessionId", "e2e-reuse", "problem", "SELECT 1");
        Map<String, String> req2 = Map.of("sessionId", "e2e-reuse", "problem", "SELECT 2");

        post("/api/diagnose", req1, DIAG_RESPONSE);
        post("/api/diagnose", req2, DIAG_RESPONSE);

        assertThat(sessionRepository.findBySessionId("e2e-reuse")).isPresent();
        List<DiagnosisRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc("e2e-reuse");
        assertThat(records).hasSize(2);
        assertThat(records).allMatch(r -> DiagnosisStatus.COMPLETED.equals(r.getStatus()));
    }

    // ==================== Scenario 5: 非法 SQL 容错 ====================

    @Test
    void shouldTolerateIllegalSql() {
        Map<String, String> request = Map.of(
                "sessionId", "e2e-illegal",
                "problem", "SELECT * FROM");

        ResponseEntity<ApiResponse<DiagnosisResponse>> resp =
                post("/api/diagnose", request, DIAG_RESPONSE);

        // 整体成功，ExplainTool 失败不影响 Agent
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getCode()).isEqualTo(0);
        assertThat(resp.getBody().getData().agentName()).isNotNull();

        List<DiagnosisRecord> records = recordRepository.findBySessionIdOrderByCreatedAtAsc("e2e-illegal");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getStatus()).isEqualTo(DiagnosisStatus.COMPLETED);
    }
}
