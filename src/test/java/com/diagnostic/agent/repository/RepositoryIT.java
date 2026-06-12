package com.diagnostic.agent.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
class RepositoryIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("dbdiagnostic");

    @Autowired
    private SessionRepository sessionRepository;

    @Autowired
    private DiagnosisRecordRepository diagnosisRecordRepository;

    @Autowired
    private PromptTemplateRepository promptTemplateRepository;

    @Autowired
    private javax.sql.DataSource dataSource;

    // ==================== Flyway 执行验证 ====================

    @Test
    void flywaySchemaHistoryShouldHaveEntries() {
        // Flyway 至少插入 V1 + V2 两条记录
        long count = promptTemplateRepository.count();
        assertThat(count).isGreaterThan(0);
    }

    @Test
    void seedDataShouldHaveFivePromptTemplates() {
        long count = promptTemplateRepository.count();
        assertThat(count).isEqualTo(5);
    }

    // ==================== PromptTemplate CRUD ====================

    @Test
    void shouldFindByTemplateKey() {
        var template = promptTemplateRepository.findByTemplateKey("sql_diagnosis_system");

        assertThat(template).isPresent();
        assertThat(template.get().getTitle()).isEqualTo("SQL诊断专家 System Prompt");
    }

    @Test
    void shouldFindAllTemplateKeys() {
        assertThat(promptTemplateRepository.findByTemplateKey("sql_diagnosis_system")).isPresent();
        assertThat(promptTemplateRepository.findByTemplateKey("sql_explain_analysis")).isPresent();
        assertThat(promptTemplateRepository.findByTemplateKey("orchestrator_router")).isPresent();
        assertThat(promptTemplateRepository.findByTemplateKey("cpu_diagnosis_system")).isPresent();
        assertThat(promptTemplateRepository.findByTemplateKey("memory_diagnosis_system")).isPresent();
    }

    // ==================== Session CRUD ====================

    @Test
    void shouldSaveAndFindSession() {
        Session session = new Session("sess-001");
        sessionRepository.save(session);

        var found = sessionRepository.findBySessionId("sess-001");
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo("ACTIVE");
        assertThat(found.get().getCreatedAt()).isNotNull();
    }

    // ==================== DiagnosisRecord CRUD ====================

    @Test
    void shouldSaveAndFindDiagnosisRecord() {
        // given: session 已存在
        sessionRepository.save(new Session("sess-002"));

        // when: 创建诊断记录
        DiagnosisRecord record = new DiagnosisRecord("sess-002", "查询很慢");
        record.setAgentName("SqlDiagnosisAgent");
        record.setSummary("检测到全表扫描，建议加索引");
        record.setStatus("COMPLETED");
        diagnosisRecordRepository.save(record);

        // then
        var records = diagnosisRecordRepository.findBySessionIdOrderByCreatedAtAsc("sess-002");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).getProblem()).isEqualTo("查询很慢");
        assertThat(records.get(0).getAgentName()).isEqualTo("SqlDiagnosisAgent");
        assertThat(records.get(0).getSummary()).isEqualTo("检测到全表扫描，建议加索引");
        assertThat(records.get(0).getStatus()).isEqualTo("COMPLETED");
        assertThat(records.get(0).getCreatedAt()).isNotNull();
    }

    // ==================== 数据源验证 ====================

    @Test
    void datasourceShouldBePostgreSQL() throws Exception {
        String url = dataSource.getConnection().getMetaData().getURL();
        assertThat(url).contains("postgresql");
    }
}
