package com.diagnostic.agent.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class DiagnosisSseE2EIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("dbdiagnostic")
            .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");

    @LocalServerPort
    private int port;

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

    // ==================== Scenario 1: SSE 完整事件流 ====================

    @Test
    void shouldStreamAllRequiredEvents() throws Exception {
        URI uri = new URI("http://localhost:" + port
                + "/api/diagnose/stream?sessionId=sse-e2e-001&problem=SELECT%20*%20FROM%20users%20WHERE%20age%20%3E%2018");

        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);

        assertThat(conn.getResponseCode()).isEqualTo(200);
        assertThat(conn.getContentType()).contains("text/event-stream");

        List<String> eventTypes = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    eventTypes.add(line.substring(6).trim());
                }
                // 读到 COMPLETE 后主动断开，避免等超时
                if (line.startsWith("event:") && "COMPLETE".equals(line.substring(6).trim())) {
                    break;
                }
            }
        }
        conn.disconnect();

        assertThat(eventTypes).contains(
                DiagnosisEventType.START.name(),
                DiagnosisEventType.ROUTING.name(),
                DiagnosisEventType.RESULT.name(),
                DiagnosisEventType.COMPLETE.name());

        int startIdx = eventTypes.indexOf(DiagnosisEventType.START.name());
        int resultIdx = eventTypes.indexOf(DiagnosisEventType.RESULT.name());
        int completeIdx = eventTypes.indexOf(DiagnosisEventType.COMPLETE.name());

        assertThat(startIdx).isLessThan(resultIdx);
        assertThat(completeIdx).isEqualTo(eventTypes.size() - 1);
    }

    // ==================== Scenario 2: Content-Type 正确 ====================

    @Test
    void shouldReturnTextEventStreamContentType() throws Exception {
        URI uri = new URI("http://localhost:" + port
                + "/api/diagnose/stream?sessionId=sse-e2e-002&problem=SELECT%201");

        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(30_000);

        String contentType = conn.getContentType();
        assertThat(contentType).isNotNull();
        assertThat(contentType.toLowerCase()).contains("text/event-stream");

        // 消费完毕避免泄漏
        conn.getInputStream().close();
        conn.disconnect();
    }
}
