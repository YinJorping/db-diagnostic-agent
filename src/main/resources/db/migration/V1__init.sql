-- ============================================================
-- V1：pgvector 扩展 + 核心表
-- ============================================================

-- pgvector 扩展（为 V1.5 RAG 预留，当前不建向量列）
CREATE EXTENSION IF NOT EXISTS vector;

-- 会话表
CREATE TABLE session (
    id          BIGSERIAL       PRIMARY KEY,
    session_id  VARCHAR(64)     NOT NULL UNIQUE,
    status      VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- 诊断记录表
CREATE TABLE diagnosis_record (
    id          BIGSERIAL       PRIMARY KEY,
    session_id  VARCHAR(64)     NOT NULL REFERENCES session(session_id),
    agent_name  VARCHAR(255),
    problem     TEXT            NOT NULL,
    summary     TEXT,
    status      VARCHAR(32)     NOT NULL DEFAULT 'IN_PROGRESS',
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

-- Prompt 模板表
CREATE TABLE prompt_template (
    id            BIGSERIAL       PRIMARY KEY,
    template_key  VARCHAR(64)     NOT NULL UNIQUE,
    title         VARCHAR(128),
    content       TEXT            NOT NULL,
    created_at    TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);
