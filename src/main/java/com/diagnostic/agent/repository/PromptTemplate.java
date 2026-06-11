package com.diagnostic.agent.repository;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "prompt_template")
@Getter
@NoArgsConstructor
public class PromptTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "template_key", length = 64, nullable = false, unique = true)
    private String templateKey;

    @Column(length = 128)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public PromptTemplate(String templateKey, String title, String content) {
        this.templateKey = templateKey;
        this.title = title;
        this.content = content;
    }
}
