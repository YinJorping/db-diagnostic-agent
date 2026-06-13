# DB Diagnostic Agent

**Orchestrator + Multi-Expert Agent 协作的数据库智能诊断平台**

Java 21 · Spring Boot 3.3.5 · PostgreSQL 16 + pgvector · Redis · Flyway · Testcontainers

---

## 架构全景

```
┌──────────────────────────────────────────────┐
│              Controller 层                     │
│  POST /api/diagnose   GET /api/diagnose/stream │
└───────────────────┬──────────────────────────┘
                    │
┌───────────────────▼──────────────────────────┐
│              Agent 层 (5 专家)                 │
│  OrchestratorAgent → AgentRouter.routeAll()   │
│  ┌─────────┬────────┬────────┬────────┬──────┐│
│  │SqlAgent │CpuAgent│MemAgent│JvmAgent│DiskAg││
│  │Order(1) │Ord(2)  │Ord(3)  │Ord(4)  │Ord(5)││
│  └────┬────┘───┬────┘───┬────┘───┬────┘──┬───┘│
│  BaseExpertAgent (模板方法 + Risk 聚合)        │
└───────────────────┬──────────────────────────┘
                    │
┌───────────────────▼──────────────────────────┐
│              Tool 层 (6 + 2 工具类)            │
│  ExplainTool  SlowQueryTool  CpuUsageTool      │
│  MemoryUsageTool  JvmUsageTool  DiskUsageTool  │
│  ┌──────────────────────────────────────┐     │
│  │ DiagnosticUtils (finding/suggestion/ │     │
│  │   dedupByAction/determineRisk)       │     │
│  │ FormatUtil (formatPercent)           │     │
│  └──────────────────────────────────────┘     │
└───────────────────┬──────────────────────────┘
                    │
┌───────────────────▼──────────────────────────┐
│              数据源层                           │
│  JMX (MBean)    DataSource (JDBC)              │
│  FileStore (NIO)  pg_stat_database              │
└──────────────────────────────────────────────┘
```

## 核心能力

接收自然语言描述的数据库问题，自动路由匹配诊断专家，并行执行安全只读工具分析，生成结构化诊断报告。

### 诊断五维度（Phase 1 闭环）

```
用户输入: "数据库慢且CPU高内存不足JVM堆满且磁盘空间不足"
         ↓
         OrchestratorAgent
         ↓
    AgentRouter.routeAll()
    ┌────────┬────────┬────────┬────────┬────────┐
    │ SqlAgent │ CpuAgent│ MemAgent│ JvmAgent│DiskAgent│
    │ @Order(1)│@Order(2)│@Order(3)│@Order(4)│@Order(5)│
    └────┬────┘└───┬────┘└───┬────┘└───┬────┘└───┬────┘
         ↓        ↓        ↓        ↓        ↓
    ExplainTool  CpuUsage  Memory   JvmUsage  DiskUsage
    SlowQuery    Tool      Usage    Tool      Tool
         ↓        ↓        ↓        ↓        ↓
    CompletableFuture.allOf() → DiagnosisReport 聚合
```

| Agent | Order | Tool | 数据源 | 诊断维度 |
|-------|:---:|------|--------|---------|
| SqlDiagnosisAgent | 1 | ExplainTool, SlowQueryTool | DataSource | SQL 执行计划、慢查询 |
| CpuDiagnosisAgent | 2 | CpuUsageTool | JMX | 系统/进程 CPU、Load Average |
| MemoryDiagnosisAgent | 3 | MemoryUsageTool | DataSource | PG 缓存命中率、内存配置 |
| JvmDiagnosisAgent | 4 | JvmUsageTool | JMX | JVM 堆/非堆、GC、线程 |
| DiskDiagnosisAgent | 5 | DiskUsageTool | FileStore + DataSource | 磁盘空间、PG I/O 统计 |

### 关键词路由矩阵

| 用户输入 | 命中 Agent |
|----------|-----------|
| "SELECT * FROM orders 很慢" | SqlAgent |
| "系统 CPU 100%" | CpuAgent |
| "缓存命中率低" | MemoryAgent |
| "Full GC 频繁" | JvmAgent |
| "磁盘空间不足" | DiskAgent |
| "数据库慢且CPU高" | SqlAgent + CpuAgent |
| "数据库慢且CPU高内存不足JVM堆满且磁盘满" | 5 Agent 全命中 |
| "今天天气怎么样" | GeneralAgent (fallback) |

## 快速开始

**前置条件**: Java 21, Docker Desktop (IT 需要)

```bash
# 开发环境启动（Testcontainers 自动提供 PostgreSQL）
mvn spring-boot:run

# 运行所有单元测试
mvn test

# 运行全部测试（含 IT，需要 Docker）
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

事件序列: `START` → `ROUTING` → `AGENT_START` → `AGENT_DONE` → `COMPLETE`

## 项目结构

```
src/main/java/com/diagnostic/agent/
├── agent/         # Agent 层
│   ├── Agent.java                  # Agent 接口（getName, getKeywords, diagnose）
│   ├── BaseExpertAgent.java        # 模板方法基类（Tool 执行 + LLM + Risk 聚合）
│   ├── AgentRouter.java            # 关键词路由（route / routeAll）
│   ├── OrchestratorAgent.java      # 诊断编排（多 Agent 并行 CompletableFuture）
│   ├── SqlDiagnosisAgent.java      # @Order(1) SQL 诊断专家
│   ├── CpuDiagnosisAgent.java      # @Order(2) CPU 资源诊断专家
│   ├── MemoryDiagnosisAgent.java   # @Order(3) 内存诊断专家
│   ├── JvmDiagnosisAgent.java      # @Order(4) JVM 诊断专家
│   ├── DiskDiagnosisAgent.java     # @Order(5) 磁盘诊断专家
│   ├── DiagnosisReport.java        # 诊断报告（AgentResult 聚合）
│   ├── PromptKeys.java             # Prompt 模板键常量
│   └── PromptService.java          # Prompt 加载与变量渲染
├── tool/          # Tool 层
│   ├── Tool.java                   # Tool 接口
│   ├── ToolResult.java             # 结构化结果（summary + detail + risk）
│   ├── ToolRegistry.java           # 自动发现 @Component Tool Bean
│   ├── RiskLevel.java              # LOW / MEDIUM / HIGH / UNKNOWN
│   ├── DiagnosticUtils.java        # 公共工具类 (finding/suggestion/determineRisk/dedupByAction)
│   ├── ExplainTool.java            # EXPLAIN 执行计划分析
│   ├── SlowQueryTool.java          # pg_stat_statements 慢查询采集
│   ├── CpuMetrics.java / CpuMetricsProvider / JmxCpuMetricsProvider / CpuUsageTool.java
│   ├── MemoryProperties.java / MemoryUsageTool.java
│   ├── JvmMetrics.java / JvmMetricsProvider / JmxJvmMetricsProvider / JvmUsageTool.java
│   └── DiskMetrics.java / DiskMetricsProvider / FileStoreDiskMetricsProvider / DiskUsageTool.java
├── controller/    # REST + SSE 端点
├── memory/        # ChatMemoryStore (InMemory / Redis)
├── repository/    # JPA Entity + Repository
├── common/        # ApiResponse, BusinessException, GlobalExceptionHandler
│   └── util/      # FormatUtil (公共格式化)
├── config/        # TraceIdFilter, DevContainersConfig, AgentExecutorConfig
└── workflow/      # Phase 2 预留
```

## 配置 Profile

| Profile | 数据源 | Flyway | Redis | ChatMemory |
|---------|--------|--------|-------|------------|
| `dev` (默认) | Testcontainers PG16 | on | 排除自动配置 | InMemory |
| `test` | Testcontainers PG16 | on | 排除自动配置 | InMemory |
| `prod` | 环境变量 `${DB_URL}` | on | Lettuce | Redis (TTL 30min) |

## 配置参考

```yaml
diagnostic:
  llm:
    provider: mock           # Phase 2 切换 deepseek/openai
  tool:
    timeout-seconds: 5
  executor:
    core-pool-size: 2        # Agent 并行线程池
    max-pool-size: 4
    queue-capacity: 100
  cpu:
    threshold-system-high: 0.9
    threshold-system-medium: 0.7
    threshold-process-high: 0.8
    threshold-load-high-multiplier: 2.0
    threshold-load-medium-multiplier: 1.0
  memory:
    threshold-buffer-hit-high: 0.99
    threshold-buffer-hit-medium: 0.95
    threshold-temp-files-medium: 5
    threshold-shared-buffers-warn-mb: 128
    threshold-work-mem-warn-mb: 64
  jvm:
    threshold-heap-high: 0.85
    threshold-heap-medium: 0.70
    threshold-non-heap-high: 0.90
    threshold-thread-count: 500
  disk:
    data-dir: /var/lib/postgresql/data
    threshold-usage-high: 0.85
    threshold-usage-medium: 0.70
    threshold-free-bytes-low: 10737418240  # 10GB
```

## 测试

```
Unit:  190 PASS — *Test.java
IT:    62 PASS  — *IT.java
Total: 252 PASS
```

### 测试覆盖

| 测试类 | 测试数 | 说明 |
|--------|:---:|------|
| AgentRouterTest | 27 | 关键词路由、多 Agent 匹配、5 Agent 全命中 |
| CpuUsageToolTest | 14 | 5 条 CPU 规则、JMX 异常、自定义阈值 |
| JvmUsageToolTest | 15 | 4 条 JVM 规则、GC 不参与风险、heapMax=-1 |
| MemoryUsageToolTest | 19 | 5 条内存规则、PG 多库、单位解析 |
| DiskUsageToolTest | 13 | 3 条磁盘规则、PG I/O 不参与风险、DataSource 降级 |
| DiagnosticUtilsTest | 14 | finding/suggestion/determineRisk/dedupByAction/formatPercent |
| CpuDiagnosisAgentTest | 6 | Prompt 验证、Tool 注入 |
| JvmDiagnosisAgentTest | 7 | 关键词收紧验证、Prompt 包含 Tool 结果 |
| MemoryDiagnosisAgentTest | 6 | Prompt 验证 |
| DiskDiagnosisAgentTest | 6 | Prompt 验证 |
| BaseExpertAgentTest | 11 | Risk 聚合、异常降级、Tool 跳过 |
| **Unit 小计** | **190** | |
| OrchestratorAgentIT | 9 | 1-5 Agent 并行、异常隔离、历史上下文 |
| RepositoryIT | 7 | Flyway 7 模板、JPA CRUD |
| SlowQueryToolIT | 11 | pg_stat_statements 真实查询 |
| ExplainToolIT | 10 | EXPLAIN 执行计划真实分析 |
| CpuUsageToolIT | 2 | 真实 JMX 自检 |
| JvmUsageToolIT | 2 | 真实 JMX 自检 |
| MemoryUsageToolIT | 2 | PG 缓存命中率真实查询 |
| DiskUsageToolIT | 2 | 真实 FileStore + PG I/O |
| SqlDiagnosisAgentIT | 4 | SQL Agent 真实诊断 |
| DiagnosisRestE2EIT | 5 | REST API 端到端 |
| DiagnosisSseE2EIT | 2 | SSE 流式端到端 |
| RedisChatMemoryStoreIT | 6 | Redis 会话记忆 |
| **IT 小计** | **62** | |
| **总计** | **252** | |

## 重构记录

| Phase | 内容 | 效果 |
|-------|------|------|
| Day11 | DiagnosticUtils + FormatUtil 提取 | 消除 Tool 层 154 行重复代码，determineRisk/dedupByAction/suggestion 从 6→1 |

## 设计原则

- **安全只读**: 所有 Tool 仅执行 SELECT / EXPLAIN / 系统视图查询
- **容错隔离**: Agent 异常不中断并行诊断，Tool 异常降级为 failure
- **零架构扩散**: 新增 Agent 只需 extends BaseExpertAgent + 声明 keywords/tools/promptKey
- **Router 自动发现**: `List<Agent>` 注入所有 @Component Agent Bean，零手动注册
- **配置分层**: 敏感信息环境变量，阈值可通过 application.yml 配置

## Phase 路线

| Phase | 内容 | 状态 |
|-------|------|------|
| Phase 1 | 5 维度诊断闭环（SQL + CPU + Memory + JVM + Disk） | done |
| Phase 1.5 | Refactor Sprint（DiagnosticUtils + FormatUtil 提取） | done |
| Phase 2 | 真实 LLM 接入, MCP, Grafana, 连续采样, Disk/Network 扩展 | pending |

## CI

每次 push 自动触发 GitCode CI：`mvn compile` + `mvn test`（详见 `.gitcode-ci.yml`）。
