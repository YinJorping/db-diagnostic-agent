package com.diagnostic.agent.repository;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "diagnosis_record")
@Getter
@NoArgsConstructor
public class DiagnosisRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "agent_name", length = 255)
    @Setter
    private String agentName;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String problem;

    @Column(columnDefinition = "TEXT")
    @Setter
    private String summary;

    @Column(length = 32, nullable = false)
    @Setter
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        if (this.status == null) {
            this.status = com.diagnostic.agent.agent.DiagnosisStatus.IN_PROGRESS;
        }
    }

    public DiagnosisRecord(String sessionId, String problem) {
        this.sessionId = sessionId;
        this.problem = problem;
        this.status = com.diagnostic.agent.agent.DiagnosisStatus.IN_PROGRESS;
    }
}
