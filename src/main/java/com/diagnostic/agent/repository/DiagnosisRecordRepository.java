package com.diagnostic.agent.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DiagnosisRecordRepository extends JpaRepository<DiagnosisRecord, Long> {

    List<DiagnosisRecord> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
