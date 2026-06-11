# DB Diagnostic Agent

**Orchestrator + Multi-Expert Agent 协作的数据库智能诊断平台**

Java 21 · Spring Boot 3.3.5 · LangChain4j 1.10.0 · PostgreSQL + pgvector · Redis · Flyway · Testcontainers

---

## 核心能力

接收自然语言描述的数据库问题（如 "SELECT * FROM orders WHERE status='pending' 很慢"），自动规划诊断步骤，调用安全只读工具分析，生成结构化诊断报告。

```
用户输入（自然语言）
      ↓
Controller (REST + SSE streaming)
      ↓
OrchestratorAgent (路由 → SqlDiagnosisAgent / GeneralAgent)
      ↓
BaseExpertAgent (模板方法: 选Tool → 执行 → LLM → 聚合Risk)
      ↓
ExplainTool / SlowQueryTool (安全只读, 5s超时)
      ↓
DiagnosisResult (risk: LOW/MEDIUM/HIGH/UNKNOWN)
      ↓
ChatMemoryStore (InMemory dev / Redis prod, TTL 30d)
```

## 快速开始

**前置条件**: Java 21, Docker Desktop

```bash
# 开发环境启动（Testcontainers 自动提供 PostgreSQL）
mvn spring-boot:run

# 运行所有测试
mvn verify
```

### API

```http
POST /api/diagnose
Content-Type: application/json

{
  "sessionId": "demo-001",
  "problem": "SELECT * FROM orders WHERE status='pending' 很慢"
}
```

响应:

```json
{
  "code": 0,
  "message": "success",
  "data": {
    "sessionId": "demo-001",
    "agentName": "SqlDiagnosisAgent",
    "summary": "检测到 Seq Scan，建议为 orders(status) 创建索引",
    "risk": "HIGH"
  },
  "timestamp": "2026-06-12 10:00:00"
}
```

### SSE 流式推送

```http
GET /api/diagnose/stream?sessionId=demo-001&problem=SELECT * FROM orders
```

事件序列: `START` → `ROUTING` → `RESULT` → `COMPLETE`

## 项目结构

```
src/main/java/com/diagnostic/agent/
├── agent/         # Agent 编排 (Orchestrator, Router, BaseExpert, SqlAgent, Prompt)
├── tool/          # 诊断工具 (ExplainTool, SlowQueryTool, ToolRegistry)
├── controller/    # REST + SSE 端点
├── memory/        # ChatMemoryStore (InMemory / Redis)
├── repository/    # JPA Entity + Repository (Session, DiagnosisRecord, PromptTemplate)
├── common/        # ApiResponse, GlobalExceptionHandler, BusinessException
├── config/        # TraceIdFilter, DevContainersConfig
└── workflow/      # Phase 2 预留
```

## 配置 Profile

| Profile | Flyway | Redis | ChatMemory | 数据源 |
|---------|--------|-------|------------|--------|
| `dev` (默认) | on | 排除自动配置 | InMemory | Testcontainers PG |
| `test` | on | 排除自动配置 | InMemory | Testcontainers PG |
| `prod` | on | Lettuce | Redis (TTL 30d) | 环境变量 |

## 测试

```
Surefire (unit):   60 PASS — *Test.java
Failsafe (IT):     49 PASS — *IT.java (Testcontainers PG + Redis)
JaCoCo:            ≥40% line coverage
```

### 集成测试覆盖

| 测试 | 场景数 | 说明 |
|------|--------|------|
| OrchestratorAgentIT | 4 | SQL诊断, GeneralAgent兜底, Session复用, 异常→FAILED |
| SqlDiagnosisAgentIT | 4 | 无SQL跳过Explain, 大表扫描, 非法SQL容错 |
| ExplainToolIT | 10 | Seq Scan风险分级, 索引推荐, 安全拒绝非SELECT |
| SlowQueryToolIT | 11 | pg_stat_statements查询, limit校验, 超时 |
| RedisChatMemoryStoreIT | 6 | 存取, 批量, 清除, 隔离, 独立副本 |
| DiagnosisRestE2EIT | 5 | 全链路HTTP→Agent→Tool→DB, 校验, Session复用 |
| DiagnosisSseE2EIT | 2 | SSE事件序列, Content-Type验证 |
| RepositoryIT | 7 | Flyway迁移验证, 种子数据, JPA CRUD |

## 设计原则

- **工具安全**: ExplainTool 拒绝非 SELECT / 多语句 / 空输入, 5 秒超时
- **容错优先**: Tool 异常不中断诊断, 标记为 failure 后继续
- **配置分层**: 敏感信息一律走环境变量, 配置文件不含密钥
- **测试隔离**: 所有 IT 使用 Testcontainers, 不依赖外部服务

## Phase 路线

| Phase | 内容 | 状态 |
|-------|------|------|
| Phase 1 | 项目骨架 + Tool 抽象 + Agent 闭环 + Web 层 + Redis Memory | done |
| Phase 1.5 | RAG 知识库摄入 (pgvector + PDFBox) | pending |
| Phase 2 | 真实 LLM 接入, MCP, Grafana, 多 Agent 扩展 | pending |
