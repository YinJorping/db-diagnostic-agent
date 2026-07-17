# Research-to-Design.md

> **定位**: 本文档是 Research-Summary 与 DbDiagnostic 项目设计的桥梁。不做研究复述，不做结论重总结。只回答一个问题：**研究中的每一个重要结论，如何影响 DbDiagnostic 的具体设计决策。**
>
> **上游文档**: Research-Summary.md + Database-Diagnosis-Evidence-Audit.md
> **下游文档**: README.md / ARCHITECTURE.md / AGENTS.md / PRD-lite / 面试答辩

---

## 1. 为什么采用 Multi-Agent 架构？

### 研究结论（出处: Research ④）

诊断领域存在不可归并的"认知断层"——同一故障的根因可能跨越完全不同的知识域（Audit #7 内存扩容 → 主从双挂 + WAL 800GB；Audit #24 PG 报错 → 根因是 OS swap/OOM）。如果只有一个 Agent，Prompt 会膨胀到包含所有知识域，诊断路径混乱，且无法并行排查。

### 设计决策

```
单 Agent ×  →  单 Agent 需要同时理解:
                  - PG 内部视图 (pg_stat_activity, pg_locks, pg_replication_slots...)
                  - OS 层指标 (CPU, Memory, Disk, swap, OOM)
                  - 版本缺陷知识库
                  - 连接池参数语义
                  → Prompt 膨胀 → 推理空间被压缩 → 面对"CPU 高"不知道该从哪切入

Multi-Agent √  →  每个 Agent 持有一个领域的完整诊断知识
                  Orchestrator 根据分诊结果路由, 并行执行
                  → Prompt 紧凑 → 推理聚焦 → 天然并行
```

### 在 DbDiagnostic 中的落地

- **Agent 接口** (`Agent.java`): 统一协议，`getName()` / `getKeywords()` / `diagnose()`
- **OrchestratorAgent**: `CompletableFuture.allOf()` 并行编排，异常隔离（单 Agent 失败不阻断其他 Agent）
- **AgentRouter**: 关键词路由 0ms 延迟，`routeAll()` 支持多 Agent 联合诊断，匹配逻辑 = 用户问题包含 Agent 关键词
- **BaseExpertAgent**: 模板方法模式，子类只需声明 `assignedTools()` / `getSystemPromptTemplateKey()` / `getKeywords()`，每个 Agent 持有独立的 system prompt

**不是架构偏好，是问题域的复杂度决定的。** Audit #7 / #12 / #24 三个案例共同证明：跨域故障无法由单一 Agent 有效诊断。

---

## 2. 为什么设计 SqlDiagnosisAgent？

### 研究结论（出处: Research ⑤ + Audit 4.2）

慢查询 / CPU 高是最**高频**的生产故障类型（Audit #3, #5, #11, #13, #18）。SQL 诊断的标准路径是三层收敛：pg_stat_statements TOP N → EXPLAIN ANALYZE → 全表扫描检测 → 索引建议（阿里云三步法，Audit #13）。

### 设计决策

SqlDiagnosisAgent 不在 Agent 层理解 SQL 语法，而是**编排两个原子 Tool 的诊断结论**：

```
用户输入: "SELECT * FROM orders WHERE status='pending' 很慢"
     ↓
SqlDiagnosisAgent (@Order 1, 最高优先级路由)
     ↓
  SlowQueryTool.execute()    →  pg_stat_statements TOP N, 识别慢查询聚合
  ExplainTool.execute(sql)   →  EXPLAIN (FORMAT JSON), 检测 Seq Scan + Sort 节点
     ↓
  LLM 基于 Tool 结果生成诊断 →  "orders 表全表扫描 50 万行, 建议为 status 创建索引"
```

### 关键设计选择

| 选择 | 理由 | 出处 |
|------|------|------|
| SlowQueryTool + ExplainTool 作为**两个独立 Tool**，而非合并为一个 `DiagnoseSqlTool` | Tool 是原子诊断原语，Agent 负责编排。SlowQueryTool 的输出可被 CPU Agent 复用（确认 CPU 消耗来源） | Research ⑥ |
| `shouldExecuteTool()` 条件判断：只有用户输入包含 SQL 关键字时才调用 ExplainTool | 避免对非 SQL 问题（如"数据库整体变慢"）无效执行 EXPLAIN | 防御性设计 |
| 路由关键词同时包含**技术词**（sql, select, 索引）和**业务词**（数据库, 查询, 慢查询） | 自然语言入口，用户不需要知道"慢查询"对应的 PG 视图叫什么 | Research ②（面向开发者设计） |

---

## 3. 为什么设计 CpuDiagnosisAgent？

### 研究结论（出处: Research ⑤ + Audit #11, #12, #13）

"CPU 100%" 是仅次于慢查询的高频告警入口。CPU 高的根因**不是单一维度能判断的**：可能是 SQL 问题（Audit #11, #13），可能是 PG 版本缺陷（Audit #12 stats collector），可能是连接数陡增。诊断路径必须同时覆盖这些方向。

### 设计决策

CpuDiagnosisAgent 的定位：CPU 症状 → 多方向并行排查 → 排除法收敛根因：

```
用户输入: "系统 CPU 100%"
     ↓
CpuDiagnosisAgent (@Order 2)
     ↓
  CpuUsageTool.execute() → JMX 采集进程 CPU / System CPU / Load Average
     ↓
  基于阈值规则判断风险等级 (HIGH > 90%, MEDIUM > 70%)
     ↓
  LLM 输出: CPU 是否真的异常 + 可能方向 (SQL / 连接数 / 版本缺陷)
```

### 为什么 V1 用 JMX 而非 OSMetrics Collector？

Research ⑤ 将 OS 跨机采集（ps/top/iostat/vmstat）列为 Phase 2。原因是架构复杂度跳升：Agent 运行机器 ≠ DB 机器，需要 SSH 或 Agent 部署。V1 用 JMX 取 JVM 进程级 CPU 作为**近似替代**——它不能替代 OS 级指标（wait/iowait/user），但足以判断"CPU 是否异常高"。

### 关键设计选择

| 选择 | 理由 |
|------|------|
| CPU 诊断只做 JMX，不做 OS 级指标 | V1 不碰跨机采集（Research ⑧ Phase 2 边界） |
| CpuUsageTool 有三层阈值（system/process/load） | 区分"整机 CPU 高"和"仅 PG 进程 CPU 高"，缩小排查范围 |
| CPU Agent 诊断结果标注"可能原因"而非"确定根因" | 来自 Research ③ 的产品定位：分诊而非定论 |

---

## 4. 为什么设计 ActivityMonitor Tool？

### 研究结论（出处: Research ⑥ + Evidence-Audit #1）

pg_stat_activity 是所有诊断路径的**起点**（Audit #1 "系统现在在做什么？"）。阿里云三步法第一步就是查活跃连接数（Audit #13）。Audit 4.3 的核心视图依赖分析中，pg_stat_activity 被 5 个 Tool 共同依赖（ActivityMonitor, ConnPoolAnalyzer, LockMonitor, StatsAnalyzer, TriageTool）。

### 设计决策

ActivityMonitor 是诊断漏斗 Layer 1（感知层）的自动化实现。它的定位不是"又一个 Tool"，而是**第一个被调用的 Tool**——其他一切深钻依赖它快速判断"当前系统状态"：

```
用户问题 → AgentRouter.routeAll()
               ↓
         所有 Agent 的 diagnose() 第一步 → ActivityMonitor.execute()
               ↓
         [当前连接数 / 活跃查询 / 等待事件 / 长事务]
               ↓
         Orchestrator 基于状态决定后续深钻方向
```

### 为什么 V1 尚未独立实现？

V1 的 ActivityMonitor 功能被**分散到了其他 Tool 中**：CpuUsageTool 采集连接信息、SlowQueryTool 采集活跃查询。这导致两个问题：
- 每个 Agent 各自采集连接状态，数据不共享
- 缺少统一的"系统状态快照"作为分诊输入

**Research 建议的 V1 路径**: ActivityMonitor 作为独立 Tool，所有 Agent 的 `diagnose()` 第一步共享其结果，避免重复采集，并为分诊决策提供统一的感知层数据 [Audit #1]。

---

## 5. 为什么设计 Prompt Strategy？

### 研究结论（出处: 问题1.md + Research ③）

项目联调中发现：当 SlowQueryTool 获取数据失败时，LLM 输出几百字解释"为什么 pg_stat_statements 没有安装"，而非回答"为什么 SQL 慢"。本质是 **Prompt 把 Tool 结果提升到了比用户问题更高的优先级**。

Research ③ 的产品定位进一步强化了这个结论：Agent = 自动分诊 + 辅助深钻，**不是替代 DBA 判断**。这意味着 Prompt 必须引导 LLM 区分"我看到了什么"和"我建议你做什么"。

### 四层 Prompt 设计策略

```
System Prompt 层:
  ├── 角色定义: "你是一个 PostgreSQL 性能诊断专家"
  ├── 用户问题优先: "始终以用户问题为第一优先级"
  ├── Tool 结果定位: "Tool 结果是诊断证据，不是诊断目标"
  └── 失败兜底: "Tool 失败时，基于经验给出可能原因和排查方向"

User Prompt 层 (BaseExpertAgent.buildUserPrompt):
  ├── 历史上下文: 从 ChatMemoryStore 加载，注入前轮对话
  ├── 用户问题: 原文引用，不做改写
  ├── Tool 执行结果: 格式化文本，成功/失败明确标注
  └── 指令: "优先用专业知识回答用户问题；Tool 成功时基于数据给出具体建议，Tool 失败时列出常见原因并说明证据不足"

Template 管理层 (PromptService + PromptKeys):
  ├── 每个 Agent 独立 system prompt key (sql_diagnosis_system, cpu_diagnosis_system...)
  ├── 通过 PromptOverrideManager 支持 A/B 评测覆盖
  └── 通过 PromptTemplateRepository (Flyway/JPA) 持久化模板

验证层 (Eval 体系):
  ├── 4 维评分: AgentMatch / RiskMatch / KeywordCoverage / RecommendationCoverage
  ├── 10 条 YAML benchmark 覆盖 5 领域
  └── POST /api/eval/run → 可量化的 Prompt 质量迭代
```

### 关键设计选择

| 选择 | 理由 |
|------|------|
| Prompt 模板通过 `PromptKeys` 常量管理，不硬编码在 Agent 中 | 模板可独立修改、评测、A/B 对比，Agent 代码不变 |
| BaseExpertAgent 统一构建 User Prompt（`buildUserPrompt`），子类不覆写 | 确保"用户问题优先 + Tool 失败兜底"策略在所有 Agent 中一致 |
| `formatToolResults` 明确标注 Tool 状态（"失败（仍可基于经验分析）"） | 防止 LLM 在看到 Error 时进入"分析 Tool 本身"的模式（问题1.md 的教训） |
| Evaluator 的 `promptOverrides` 参数支持不改数据库做 A/B 对比 | Research ③ 的"Agent 诊断质量 > 功能数量"理念：Prompt 需要像代码一样被测试和迭代 |

---

## 6. 为什么 V1 只支持 PostgreSQL？

### 研究结论（出处: Research ⑧ + Audit 第二章）

全部 24 条研究证据的**数据源均为 PostgreSQL**。官方文档（6/24）、邮件列表（5/24）、Bug 报告（2/24）全部围绕 PG 生态。Tool 设计遵循"1:1 映射 PG 诊断原语"原则（Research ⑥），这意味着：

| Tool | 映射的 PG 诊断原语 | MySQL 对应物 |
|------|-------------------|-------------|
| ExplainTool | `EXPLAIN (FORMAT JSON)` | `EXPLAIN FORMAT=JSON`（输出结构不同） |
| SlowQueryTool | `pg_stat_statements` | `performance_schema.events_statements_summary_by_digest` |
| MemoryUsageTool | `pg_stat_database` + `pg_settings` | `information_schema` + `sys` schema |
| DiskUsageTool | `pg_stat_database` I/O 列 | 无直接对应 |

### 设计决策

V1 选择 **深度优先于广度**：

```
广度优先 (×): 同时支持 PG/MySQL/Redis
  → 每个 Tool 需要 N 套实现 → 6 个 Tool × 3 数据库 = 18 个实现
  → V1 工作量 3 倍, 每个数据库的诊断深度只有 1/3
  → Audit #12, #23 等版本特定知识无法覆盖

深度优先 (√): 仅支持 PostgreSQL, 做深
  → 每个 Tool 一套实现, 完整覆盖 PG 诊断路径
  → 版本感知 (PG14/15/17 已知缺陷)
  → Audit 24 条证据全部可映射 → 诊断闭环完整
```

### 为什么 MySQL 在路由关键词里？

`SqlDiagnosisAgent.ROUTING_KEYWORDS` 包含 `"mysql"`，这是为 Phase 2 预留的路由锚点，不是功能声明。当用户问题包含 "mysql" 时，Agent 会被路由到，但 Tool 执行时会因为查询 `pg_stat_statements` 而失败。当前这是一个**已知的体验缺陷**。

### 多数据库扩展路径（Phase 2+）

研究已定义了正确的扩展模式：
- **Agent 层不变**: SqlDiagnosisAgent 的多数据库版本共享同一套 Agent 逻辑（症状 → 诊断路径）
- **Tool 层切换**: `Tool` 接口不变，新增 `MysqlExplainTool` / `MysqlSlowQueryTool` 等实现
- **路由层感知**: AgentRouter 根据 DataSource 类型（JDBC URL 前缀 jdbc:postgresql vs jdbc:mysql）决定注入哪套 Tool

结论：**这不是技术瓶颈，是范围决策。V1 用 PG 证明模式，V2 扩展到 MySQL 时架构不需要重构。**

---

## 7. 为什么不支持自动修复？

### 研究结论（出处: Research ③ + ⑦）

核心证据链：
1. **美团数据**: 80% 故障中 80% 时间花在"分析和定位"，不是花在"执行修复" [Audit #9]
2. **修复决策需要人判断**: VACUUM FULL 导致连接耗尽后 DBA 选择 kill 会话 [Audit #16]，死锁涉及内核 Bug 需要升级 PG17 [Audit #22]，这些决策有上下文判断（业务优先级、变更窗口、回滚风险）
3. **产品边界**: Agent 介入点在"监控告警之后、人工深钻之前"（Research ③），这是分诊和诊断层，不是执行层

### 设计决策

```
DbDiagnostic 的产品边界:
  IN  → 自动分诊 + 辅助深钻 + 推荐方案
  OUT → 自动修复 (pg_terminate_backend / ALTER SYSTEM / DDL)

具体来说:
  √ 输出 "发现 inactive 复制槽, WAL 堆积 196GB, 建议清理复制槽 xxx" [Audit #14]
  × 执行 "SELECT pg_drop_replication_slot('xxx')"

  √ 输出 "检测到 5 个长时间 idle-in-transaction 连接, 建议 kill PID 1234,5678"
  × 执行 "SELECT pg_terminate_backend(1234)"
```

### 为什么这是正确的产品决策

| 维度 | 自动修复 | 推荐方案（当前设计） |
|------|---------|-------------------|
| 责任边界 | Agent 出 Bug 直接执行破坏性操作 | 人在回路，Agent 只给建议 |
| 信任建立 | 一次误 kill 导致用户永久放弃 | 建议准确 → 逐步建立信任 |
| 合规 | 生产数据库的写操作需要审批 | 读操作无合规风险 |
| 复杂度 | 需要回滚机制、审批流、权限控制 | 不需要 |

**Research ⑦ 明确将"自动修复"列为 OUT**，理由来自 Audit #16（kill 决策需要人）和 #22（修复可能需要升级版本，不是 Agent 能执行的）。这不是能力问题，是产品边界问题。

---

## 8. 为什么 Memory / Disk / JVM Agent 在 V1 中做了但与研究范围不符？

### 研究结论（出处: Research ⑧ V1 范围）

Research 的 V1 范围界定是：**连接 + SQL + CPU 三条诊断路径走完闭环。Memory 和 Disk 等 OS 跨机采集就绪后再接入。** 即 Memory Agent 和 Disk Agent 是 Phase 2 任务。

### 实际实现

项目当前有 5 个 Agent：Sql / Cpu / Memory / Jvm / Disk。这比研究建议的 V1 范围多了 3 个 Agent。原因是 **JMX 降低了 OS 跨机采集的实现门槛**：

| Agent | Research 范围 | 实际实现方式 | 差异 |
|-------|-------------|------------|------|
| MemoryDiagnosisAgent | Phase 2（需 OS 跨机采集） | DataSource 查 pg_stat_database + pg_settings | 不依赖 OS 采集，直接从 PG 内部视图获取缓存命中率 |
| DiskDiagnosisAgent | Phase 2（需 OS 跨机采集） | FileStore NIO + pg_stat_database I/O | 用 Java NIO 替代 OS 命令采集磁盘，从 PG 视图获取 I/O 统计 |
| JvmDiagnosisAgent | 未在研究范围内 | JMX (JvmMetricsProvider) | 研究聚焦 DBA 视角，JVM 来自开发视角的补充 |

### 这是否与研究矛盾？

**不矛盾。** Research 将 Memory/Disk 列为 Phase 2 的核心理由是"需要 OS 跨机采集"。项目实现时发现：
- PG 自身的 `pg_stat_database` 提供了缓冲命中率（Memory Agent 的核心指标）
- Java NIO `FileStore` 提供了磁盘空间信息（Disk Agent 的核心指标）
- JMX 提供了 JVM 堆/非堆/GC/线程（JVM Agent 的所有指标）

即在 JDBC 连接可用 + 本机 JMX 可达的前提下，Memory/Disk/JVM 的**基础诊断可以绕过 OS 跨机采集**。更深层的诊断（swap 状态、iowait、OOM killer 日志）仍然需要 Phase 2 的 OSMetrics Collector。

### 设计决策

V1 的 Memory/Disk/JVM Agent 是"**本机可达版本**"——能做基础指标诊断，但距离 Research 定义的完整能力（跨机关联分析）还有差距。这等价于 Research Phase 1.5：在 Phase 1 核心路径之外，用较低成本多覆盖了三个诊断维度。

---

## 9. 为什么 Tool 层采用原子粒度的设计？

### 研究结论（出处: Research ⑥）

一个 Tool = 一个 PG 诊断原语，不做复合。Tool 的边界 = 数据源的边界。诊断逻辑在 Agent 层，不在 Tool 层。

### 设计决策的证据（从当前代码反推）

项目目前的 6 个 Tool 中，ExplainTool 和 SlowQueryTool 符合原子原则（各自对应一个 PG 诊断原语），但存在一个设计问题：**缺少独立的 ActivityMonitor Tool**。

Evidence-Audit 定义了 17 个 Tool，Research 将其中的 ActivityMonitor 定位为"所有诊断路径的起点"（pg_stat_activity 查询），但当前项目中这个功能被分散了：
- SlowQueryTool 只查 pg_stat_statements
- CpuUsageTool 只查 JMX
- 没有 Tool 统一提供"当前系统状态快照"

这导致每个 Agent 缺乏共享的感知层数据，Orchestrator 的分诊决策缺少统一输入。**Research 建议的改进方向**：提取 ActivityMonitor 为独立 Tool，作为所有 Agent 的 `diagnose()` 第一步调用。

### 当前 Tool 映射表

| 当前 Tool | 映射 PG 原语 | 状态 |
|-----------|------------|------|
| ExplainTool | EXPLAIN ANALYZE | 已有，原子粒度 |
| SlowQueryTool | pg_stat_statements | 已有，原子粒度 |
| CpuUsageTool | JMX (OS CPU 近似) | 已有，但非 PG 原语 |
| MemoryUsageTool | pg_stat_database + pg_settings | 已有，**复合了两个 PG 原语** |
| JvmUsageTool | JMX (非 PG 原语) | 已有 |
| DiskUsageTool | FileStore + pg_stat_database I/O | 已有，**复合了两个数据源** |
| **ActivityMonitor** | pg_stat_activity | **缺失** |
| **LockMonitor** | pg_locks | **缺失** |

---

## 10. 诊断报告的设计逻辑

### 研究结论（出处: Research ② + ⑦）

面向**开发者**设计输出格式：核心诉求不是"给我看 pg_locks 视图"，而是**"我的应用为什么挂了/慢了，我应该改什么"**。

### 在 DiagnosisReport 中的落地

```
DiagnosisReport.aggregate():
  ├── sessionId       → 会话追溯
  ├── agentResults[]  → 各 Agent 诊断结果 (AgentResult)
  │     ├── agentName → 哪个专家做的诊断
  │     ├── summary   → 面向开发者的自然语言摘要
  │     ├── risk      → HIGH / MEDIUM / LOW
  │     └── detail    → 证据链 (findings + suggestions)
  ├── finalSummary    → Orchestrator LLM 聚合多个 Agent 结论
  └── summaryTokens   → LLM 调用的 Token 消耗统计

关键设计:
  - summary 是应用视角的 ("建议为 orders(status) 创建索引")
  - detail 是 DBA 视角的 (findings: Seq Scan 50万行)
  - 两者都在，开发者读 summary, DBA 可展开 detail
```

Orchestrator 的多 Agent 聚合策略：
- **单 Agent 匹配**: 直接返回该 Agent 的 `DiagnosisResult.summary`
- **多 Agent 匹配**: 调 LLM 语义聚合（`PromptKeys.SUMMARIZER_AGGREGATION`），失败降级为字符串拼接
- **无 Agent 匹配**: GeneralAgent fallback

---

## 设计速查表

| 研究结论 | 设计决策 | 在项目中的位置 |
|---------|---------|-------------|
| Multi-Agent 是问题域复杂度决定的，不是架构偏好 | 5 Expert Agent + Orchestrator + AgentRouter | `OrchestratorAgent.java`, `AgentRouter.java`, `BaseExpertAgent.java` |
| Agent 拆分维度 = 症状模式，非 PG 视图 | 每个 Agent 的 keywords 匹配用户自然语言 | `SqlDiagnosisAgent.ROUTING_KEYWORDS` 等 |
| Agent 介入点在分诊层 | Orchestrator 被动触发（用户提问），不做主动轮询 | `OrchestratorAgent.diagnose(sessionId, problem)` |
| Tool = 原子诊断原语 | Tool 接口薄而稳定，诊断逻辑在 Agent 层 | `Tool.java`, `BaseExpertAgent.diagnose()` 模板方法 |
| 用户问题优先 + Tool 失败兜底 | `buildUserPrompt` 中的指令 + `formatToolResults` 失败标注 | `BaseExpertAgent.java:212-218` |
| 开发者视角输出 | 诊断报告双视图（summary 应用视角 + detail DBA 视角） | `DiagnosisReport.java`, `DiagnosisResult.java` |
| 不能自动修复 | 所有 Tool 只读 SELECT + EXPLAIN | 全部 6 个 Tool 的 `execute()` 方法 |
| V1 PG Only | 4/6 Tool 硬编码 PG 系统视图 | `SlowQueryTool.java`, `MemoryUsageTool.java` 等 |
| Prompt 可评测可迭代 | PromptKeys + PromptOverrideManager + EvalScorer | `PromptKeys.java`, `EvalScorer.java` |
| 安全只读 | SensitiveDataMasker 脱敏 + Tool 只允许 SELECT | `SensitiveDataMasker.java`, `ExplainTool.java` SQL 校验 |
| 异常隔离 | Agent 异常不中断并行诊断，Tool 异常降级为 failure | `OrchestratorAgent.java:99-103`, `BaseExpertAgent.java:176-185` |

---

> **生成日期**: 2026-07-15
> **输入**: Research-Summary.md + Database-Diagnosis-Evidence-Audit.md + 问题1.md
> **下游文档**: README.md / ARCHITECTURE.md / PRD-lite / 面试答辩材料

​	
