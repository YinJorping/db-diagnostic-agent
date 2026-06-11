# AGENTS.md - 数据库诊断 AI Agent 平台（生产就绪版）

本文档用于指导 AI 编程助手（Cursor, Claude Code, GitHub Copilot Agent, Roo Code, Cline）自动开发本项目。
请严格遵循以下架构、规范和分阶段任务顺序进行代码生成。

## AI 快速执行清单（Quickstart）
1. 生成 `pom.xml`，引入 Spring Boot Starter Web, Data JPA, Redis, LangChain4j (≥1.10.0), PostgreSQL + pgvector, PDFBox, Micrometer + Prometheus, Flyway, Testcontainers。
2. 创建 `application.yml` 及 `application-dev.yml` / `application-prod.yml`，敏感信息使用环境变量。
3. 按包结构创建目录（`controller`, `service`, `orchestrator`, `agent`, `tool`, `rag`, `memory`, `workflow`, `evaluation`, `repository`, `config`, `common`）。
4. 实现 `ApiResponse` 和 `GlobalExceptionHandler`。
5. 用 Flyway 创建所有表（pgvector 扩展，维度动态适配）。
6. 实现 `ToolResult`，开发核心 Tool（ExplainTool, RedisInfoTool, CpuLoadTool），使用 Testcontainers 测试。
7. 实现 `PromptService`, `RedisMemoryStoreConfig`, `VectorStoreService`。
8. 构建轻量 `DiagnosisWorkflow`（仅编排诊断步骤，不实现 DAG 引擎）。
9. 开发 `BaseExpertAgent`, `OrchestratorAgent`, 至少 `SqlDiagnosisAgent`，绑定对应 Tool。
10. 实现 API 层（REST + SSE 流式输出），集成 Micrometer 指标，Agent 评估数据采集。
11. 编写端到端测试，验证验收标准，配置 JaCoCo 覆盖率。

---

## 1. 项目概述
**项目名称**：Database Diagnostic AI Agent Platform

**技术栈**：Java 21, Spring Boot 3.x, LangChain4j (≥1.10.0), PostgreSQL + pgvector, Redis, Flyway, Testcontainers, Micrometer + Prometheus  
**二期扩展**：MCP (Model Context Protocol) 集成，Grafana 可视化，多数据源支持

**核心目标**：构建一个 **Orchestrator + 多专家 Agent** 协作的智能诊断平台。接收自然语言描述的数据库问题，自动规划诊断步骤，调用安全的工具执行分析，记录完整的推理轨迹，最终生成结构化诊断报告。

**简历价值**：展示 Agent 编排、RAG 检索增强、工具规范化、会话记忆、可观测性、工程化实践等企业级能力。

---

## 2. 系统架构

### 2.1 整体架构图
```text
用户输入（自然语言）
       ↓
  Controller (API层，REST + SSE)
       ↓
  Orchestrator Agent (编排者)
       ↓
  ┌──────────────┼──────────────┐
  ↓              ↓              ↓
SqlAgent    RedisAgent    LinuxAgent
 (专家)        (专家)         (专家)
  ↓              ↓              ↓
  Tool 执行层 (通过 ToolRegistry 获取工具，安全只读，超时控制)
       ↓
  数据层 (PostgreSQL + pgvector, Redis, 目标数据库)
       ↓
  RAG 检索 + 会话记忆 (RedisChatMemoryStore)
       ↓
  Observation Store (推理轨迹)
       ↓
  诊断报告生成 + Agent 评估记录
```