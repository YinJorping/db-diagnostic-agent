# Research-Summary.md

> **定位**: 本文档是 PRD、Architecture Decision Record、Agent Design、README 的统一设计依据。
> **输入**: Database-Diagnosis-Research.xlsx（原始案例） + Database-Diagnosis-Evidence-Audit.md（已验证证据）
> **方法**: 不重复案例，不罗列 24 条数据。从证据中提炼规律，每个结论必须有出处。

---

## ① 数据库诊断真实工作流是什么？

### 核心发现：诊断不是线性流程，而是"漏斗式收敛"

证据揭示了三层漏斗结构：

```
Layer 1: 感知层 (Awareness)
    ↓     "系统现在在做什么？" → pg_stat_activity [Audit #1]
    ↓     "OS 层面有无瓶颈？" → ps/top/iostat/vmstat [Audit #2]
    
Layer 2: 分诊层 (Triage)
    ↓     80% 的问题在分诊阶段即可明确归属方向 [Audit #19]
    ↓     区分：资源不足 / 配置错误 / 性能问题 / 内核 Bug [Audit #19]
    
Layer 3: 深钻层 (Deep Dive)
    → SQL 分支: pg_stat_statements → EXPLAIN ANALYZE [Audit #3, #5, #11, #13]
    → 锁分支:   pg_locks → 锁等待链 [Audit #4, #21, #22]
    → 磁盘分支: pg_wal → pg_replication_slots + pg_stat_archiver [Audit #14, #15]
    → 连接分支: pg_stat_activity + max_connections 对比 [Audit #8, #16]
```

**关键规律**：

- **官方文档自身就定义了这个漏斗**：Chapter 28（监控统计）先发现问题，Chapter 14（EXPLAIN）再深入分析 [Audit #6]。这不是我们发明的结构，是 PG 官方内置的诊断哲学。
- **阿里云的三步排查法验证了漏斗模式**：pg_stat_activity → pg_stat_statements → pg_stat_user_tables，每一步都比上一步更聚焦 [Audit #13]。
- **美团的数据量化了漏斗价值**：80% 故障中 80% 的时间花在"分析和定位"上 [Audit #9]。这意味着漏斗的瓶颈在 Layer 2 → Layer 3 的过渡 —— 即"从知道有问题"到"知道问题在哪"这一跳。

### 结论：产品工作流 = 自动化这个漏斗

产品不是替代 DBA，而是**把 Layer 1→2→3 的手动切换变成自动串联**。Agent 的编排逻辑就是漏斗的流转逻辑。

---

## ② Java 开发的真正痛点是什么？

### 核心发现：Java 开发者的痛点不是"不会用 PG 工具"，而是"不知道问题出在数据库的哪一层"

证据中标注 "目标用户: DBA" 的有 18 条，"目标用户: 开发" 的只有 3 条 [Audit #8, #21, #24]。这本身就是一个发现：**PG 诊断知识天然偏向 DBA 群体，开发者被排除在外**。

开发者的三个具体痛点：

| 痛点 | 证据 | 本质 |
|---|---|---|
| **连接池配置是盲区** | pgx 连接池参数不当 → Too many clients [Audit #8] | 开发者不感知 `max_connections`，不知道自己设的 MaxConns 对数据库意味着什么 |
| **ORM 生成的 SQL 是黑盒** | Django `select_for_update()` 在 PG17 上跨行死锁 [Audit #21] | 开发者看到的是 ORM 方法调用，看不到背后生成的锁行为 |
| **应用层异常无法下钻** | "查询解析时栈溢出"根因是 OS swap/OOM [Audit #24] | 开发者看到 PG 报错就以为是 PG 的问题，不会去查 OS 层 |

### 结论：产品必须面向开发者设计交互

证据表明，开发者的核心诉求不是"给我看 pg_locks 视图"，而是**"我的应用为什么挂了/慢了，我应该改什么"**。这意味着：
- Agent 的输出必须是**应用视角**的（"你的连接池 MaxConns 设成 50，但数据库 max_connections 只有 100，你的 3 个实例加起来可能超限"），而非 DBA 视角的（"当前 pg_stat_activity 显示 87 个连接"）。
- 这不是技术选择，是产品定位选择。

---

## ③ Agent 应该介入哪一步？

### 核心发现：Agent 应该在 Layer 2（分诊层）介入，这是全流程中人力消耗最大的一步

证据链：

1. **美团数据**："80% 故障中 80% 的时间花在分析和定位上" [Audit #9]
2. **行业共识**："80% 的问题在分诊阶段即可明确归属方向" [Audit #19]
3. **两者叠加意味着**：DBA 把 64%（80% × 80%）的总故障时间花在"判断问题属于哪个类别"上 —— 而这一步 80% 是有标准答案的。

### Agent 的介入位置：监控告警之后、人工深钻之前

```
[监控告警触发] → [Agent 自动分诊] → [输出初步诊断 + 推荐深钻方向] → [DBA/开发者确认并深钻]
                      ↑
                  Agent 介入点
```

**为什么不在 Layer 1 之前介入？** Layer 1 是持续监控，不需要 Agent，需要的是可靠的 metrics 采集。证据 #17 表明 70% 故障可通过日常巡检预防 —— 但巡检是自动化脚本的事，不是 Agent 的事。

**为什么不在 Layer 3 完全替代人？** 证据 #22（分区表死锁需内核开发者修复）和 #23（VACUUM 卡 4 天需升级 PG17）表明，部分深钻结论需要**人的判断和审批**。Agent 应该做到"给出深钻结果和推荐方案"，但最终执行需要人确认。

### 结论：Agent = 自动分诊 + 辅助深钻，不是自动修复

---

## ④ 为什么采用 Multi-Agent？

### 核心发现：诊断领域存在不可归并的"认知断层"——四个断层对应四类 Expert Agent

证据中最关键的规律：**同一故障的根因可能跨越完全不同的知识域**。

三个典型案例说明了为什么单 Agent 不够：

| 案例 | 跨域特征 | 涉及知识域 |
|---|---|---|
| 内存扩容 → 主从双挂 + WAL 800GB [Audit #7] | 变更操作 → 参数不一致 → WAL 堆积 | 配置、磁盘、复制 |
| 查询解析栈溢出 [Audit #24] | PG 报错 → 根因是 OS swap/OOM | OS 内存、PG 错误日志 |
| PG14 stats collector 高 CPU [Audit #12] | CPU 高 → 根因是版本缺陷 | CPU、版本知识 |

**如果只有一个 Agent**，它需要同时理解：
- PG 内部视图（pg_stat_activity, pg_stat_statements, pg_locks, pg_replication_slots...）
- OS 层面指标（CPU, Memory, Disk, swap, OOM）
- 版本特定缺陷（PG14 stats collector, PG15 VACUUM 1GB limit, PG17 分区表死锁）
- 连接池参数语义（MaxConns, MinConns, IdleTimeout）

这会导致：
- **Prompt 膨胀**：每个 Agent 的 system prompt 需要包含所有知识域，上下文窗口被诊断知识占满，推理空间被压缩
- **诊断路径混乱**：单一 Agent 面对 "CPU 高" 这类模糊信号，不知道该从 SQL 角度还是版本角度切入
- **无法并行诊断**：真实故障场景下，DBA 的标准做法是多方向并行排查 [Audit #13 的三步法暗示了并行]

### 结论：Multi-Agent = 每个 Agent 持有一个领域的完整诊断知识，由 Orchestrator 根据分诊结果路由

这不是架构偏好，是问题域本身的复杂度决定的。

---

## ⑤ 为什么需要 Sql / CPU / Memory / Disk 这些 Expert Agent？

### 核心发现：这四个 Agent 不是按技术维度拆分，而是按"故障表象 → 根因的映射方向"拆分

DBA 面对告警时的第一反应不是"我要查哪个视图"，而是"这个症状通常意味着什么"。

每个 Expert Agent 对应一种**症状模式及其诊断路径**：

| Expert Agent | 对应症状 | 证据支撑 | 核心诊断路径 |
|---|---|---|---|
| **Sql Agent** | "查询变慢了" / "某个接口超时了" | Audit #3, #5, #11, #13 | pg_stat_statements TOP N → EXPLAIN ANALYZE → 全表扫描检测 → 索引建议 |
| **CPU Agent** | "CPU 100%" / "数据库响应变慢" | Audit #11, #12, #13 | pg_stat_activity 连接数 → pg_stat_statements TOP SQL → 版本缺陷检测 |
| **Memory Agent** | "OOM" / "查询解析报错" / "VACUUM 卡住" | Audit #7, #23, #24 | OS memory 指标 → PG shared_buffers → VACUUM maintenance_work_mem → swap 状态 |
| **Disk Agent** | "磁盘告警" / "WAL 目录满" / "服务停止" | Audit #7, #14, #15, #20 | pg_wal 大小 → pg_replication_slots → pg_stat_archiver → autovacuum 状态 |

### 为什么不是按 PG 视图拆分？

如果按视图拆（ActivityAgent、LockAgent、StatementAgent...），Agent 就变成了"会说话的 SQL 客户端"，而不是诊断专家。

按症状拆分的好处：
- **入口自然**：用户说"数据库慢了"，Orchestrator 可以并行调 Sql Agent + CPU Agent，而不需要用户自己判断该查哪个视图
- **诊断路径内聚**：每个 Agent 内部知道"这个症状 → 先查 A → 如果 A 正常再查 B → 如果 B 异常再查 C"，这是一条完整的诊断决策树
- **证据 #24 的价值**：跨层故障需要两个 Agent 协同（Memory Agent 发现 OS 内存压力 + Sql Agent 看到 PG 报栈溢出），Orchestrator 合并结论输出"根因是 OS 内存不足导致 PG 查询解析失败"

### 结论：四个 Expert Agent 对应四种最常见的生产告警入口

这是从 24 条证据中归纳出的最高频故障分类 [Audit 第四章 4.2]。

---

## ⑥ Tool 为什么这样设计？

### 核心设计原则：一个 Tool = 一个 PG 诊断原语，不做复合

Evidence-Audit 中列出了 17 个 Tool [Audit 第二章]，每个都**1:1 映射到 PG 官方文档中定义的诊断能力**：

```
pg_stat_activity       → ActivityMonitor       [Audit #1 + Chapter 28]
pg_stat_statements     → StatsAnalyzer         [Audit #3 + Appendix F.30]
EXPLAIN ANALYZE        → ExplainTool           [Audit #5 + Chapter 14]
pg_locks               → LockMonitor           [Audit #4 + Section 54.12]
pg_replication_slots + pg_stat_archiver → WalDiskMonitor  [Audit #14]
OS: ps/top/iostat/vmstat → OSMetrics Collector  [Audit #2 + Chapter 28.1]
```

### 为什么是原子 Tool 而不是复合 Tool？

1. **Agent 需要灵活组合**：同是 "CPU 高"，可能是 SQL 问题（需要 StatsAnalyzer + ExplainTool），也可能是版本缺陷（需要 VersionAdvisor），还可能是连接数问题（需要 ActivityMonitor）。如果 Tool 是复合的（比如一个 "DiagnoseCPU" Tool 内部写死了查询逻辑），Agent 就失去了根据实际情况调整诊断路径的能力。

2. **Tool 输出可复用**：StatsAnalyzer 的输出既可以被 Sql Agent 用来找慢查询，也可以被 CPU Agent 用来确认 CPU 消耗来源，还可以被 Orchestrator 作为诊断报告的附件。原子 Tool 的产出是"事实"，复合 Tool 的产出是"判断"，而 Agent 的职责是做出判断。

3. **Tool 的边界 = 数据源的边界**：每个 Tool 对应一个明确的数据源（PG 视图或 OS 指标），这意味着 Tool 的输入输出可以严格类型化，Agent 调用 Tool 时不需要理解 SQL 语法，只需要理解 Tool 的语义。

### 结论：Tool 层 = 诊断原语层。薄而稳定。诊断逻辑在 Agent 层，不在 Tool 层。

---

## ⑦ 项目边界是什么？

### 核心定位：数据库诊断的第一轮自动化 —— "分诊 + 初步深钻"代理

边界的划定基于证据中反复出现的两个数字：

- **80%** 的问题在分诊阶段即可明确方向 [Audit #19]
- **70%** 的故障可通过日常巡检预防 [Audit #17]

这意味着：

**项目做的是 80% 场景的 80% 路径** —— 即分诊层的自动化和四大高频故障（慢查询、锁阻塞、WAL堆积、连接耗尽 [Audit #18]）的初步深钻。

### IN

- **自动采集** PG 诊断视图数据（pg_stat_activity, pg_stat_statements, pg_locks, pg_replication_slots, pg_stat_archiver）
- **自动分诊**：将问题归类到 SQL / CPU / Memory / Disk 四个域
- **辅助深钻**：在每个域内按标准诊断路径输出根因分析 + 推荐方案
- **跨层关联**：OS 指标 ↔ PG 指标的关联分析 [Audit #2, #24]
- **版本感知**：识别已知版本缺陷并建议升级路径 [Audit #12, #23]

### OUT

- **自动修复**：不执行 `pg_terminate_backend()`、不修改配置参数、不执行 DDL。证据 #16 中 VACUUM FULL 导致连接耗尽后 DBA 选择 kill 会话 —— 这个决策需要人做，Agent 只给出"建议 kill 哪些会话"。
- **混沌工程**：Audit #10 验证了混沌工程的价值，但它属于"验证诊断能力"而非"诊断"本身，是独立产品。
- **长期基线分析**：ChangeImpactTool 和 DailyChecklist 需要持续运行的历史基线，属于监控平台范畴而非诊断 Agent 范畴。
- **实时告警**：Agent 是被动响应（用户询问或告警触发后启动），不做主动轮询和告警。

### 与监控平台的关系

监控平台负责："磁盘使用率 95%，触发告警" → 通知用户。
本系统负责：用户收到告警后问 "为什么磁盘满了？" → Agent 诊断输出 "发现 inactive 复制槽，WAL 堆积 196GB，建议清理复制槽 `xxx`" [Audit #14]。

---

## ⑧ 哪些功能 V1 不做？

### V1 范围界定原则

基于证据的**实现复杂度 + 频率 + 独立性**三维评估：

### V1 IN（Phase 1 核心路径）

| 功能 | 覆盖证据 | 理由 |
|---|---|---|
| **ActivityMonitor** | Audit #1 | 所有诊断路径的起点，后续一切分析依赖连接状态 |
| **StatsAnalyzer** | Audit #3, #11, #13 | TOP SQL 是最高频诊断需求（慢查询/CPU 高排第一 [Audit 4.2]） |
| **ExplainTool** | Audit #5, #6 | SQL 深钻的唯一标准方法，PG 官方定义的必经路径 |
| **LockMonitor** | Audit #4 | 锁阻塞是第二高频故障 [Audit 4.2] |
| **Orchestrator + 路由** | Audit #9, #19 | 分诊逻辑是 Agent 核心价值所在 |
| **Sql Expert Agent** | 上述 | 覆盖最高频故障类型 |
| **CPU Expert Agent** | 上述 | 覆盖最高频故障类型 |
| **Diagnosis Report 输出** | Audit #18 | 覆盖四大类问题的诊断报告模板 |

### V1 OUT（Phase 2/3 或永不）

| 功能 | 理由 | 后续计划 |
|---|---|---|
| **OSMetrics Collector** | 需要跨机采集（Agent 运行机器 ≠ DB 机器），涉及 SSH/Agent 部署，架构复杂度跳升 | Phase 2 |
| **ResourcePressureDetector** | 依赖 OSMetrics Collector [Audit #24] | Phase 2 |
| **Memory Expert Agent** | 核心诊断路径依赖 OS 内存指标（swap/OOM），需 OSMetrics Collector 先就绪 | Phase 2 |
| **Disk Expert Agent** | 核心诊断路径依赖磁盘使用率 + pg_wal 大小采集 [Audit #14] | Phase 2 |
| **WalDiskMonitor** | 依赖 Disk Agent + 复制状态采集 | Phase 2 |
| **ConnPoolAnalyzer** | 需要与应用侧配置（HikariCP/Druid/pgx 参数）关联 [Audit #8]，跨系统采集 | Phase 2 |
| **DiskFullGuard** | 属于监控平台范畴（阈值告警），不是诊断 Agent 职责 | 不纳入 |
| **DeadlockAnalyzer + LockChainVisualizer** | 死锁可视化需要图形化输出 [Audit #21, #22]，且死锁相对低频 | Phase 3 |
| **VacuumAdvisor** | 需要长周期监控 VACUUM 进度 [Audit #23]，单次诊断无法体现价值 | Phase 3 |
| **ChangeImpactTool** | 需要变更事件采集（谁在什么时候改了什么），属于独立系统 | Phase 3 |
| **DailyChecklist** | 属于监控巡检自动化 [Audit #17]，不是诊断 Agent 职责 | 不纳入 |
| **TriageTool** | V1 由 Orchestrator 的分诊逻辑覆盖，独立成 Tool 是过度抽象 | 可能 Phase 2 |
| **VersionAdvisor** | 硬编码版本缺陷知识库 [Audit #12]，V1 可做但优先级低 | Phase 2 |
| **ChaosValidator** | 完全不同的产品方向 [Audit #10] | 独立产品 |

### V1 的范围一句话

> **连接 + SQL + CPU 三条诊断路径，走完"感知 → 分诊 → 深钻 → 报告"全闭环。Memory 和 Disk 等 OS 跨机采集就绪后再接入。**

---

## 附录：关键设计决策速查表

| 决策 | 结论 | 依据 |
|---|---|---|
| Agent 架构 | Multi-Agent + Orchestrator | Audit #7, #12, #24（跨域故障需要多 Agent 协同） |
| Agent 拆分维度 | 按症状（Sql/CPU/Memory/Disk），不是按 PG 视图 | Audit #13（三步法暗示按症状收敛） |
| Tool 粒度 | 原子 Tool，1:1 映射 PG 诊断原语 | Audit 第二章 17 Tool 的映射逻辑 |
| 诊断逻辑位置 | 在 Agent 层，不在 Tool 层 | 同上 |
| 产品定位 | 分诊 + 辅助深钻，不是自动修复 | Audit #9（80% 时间在定位）+ Audit #16, #22（修复需要人判断） |
| 目标用户 | 以开发者为主交互界面，但覆盖 DBA 需求 | Audit #8, #21（开发者的诊断盲区是高频问题源） |
| V1 不碰的 | OS 跨机采集、WAL/磁盘诊断、锁可视化、Vacuum 监控、混沌工程 | 各 Audit 条目的实现复杂度评估 |

---

> **生成日期**: 2026-07-15
> **输入**: Database-Diagnosis-Research.xlsx（原始研究） + Database-Diagnosis-Evidence-Audit.md（已验证证据审计）
> **本文件是以下文档的设计依据**: PRD.md / ARCHITECTURE.md / AGENT-DESIGN.md / README.md
