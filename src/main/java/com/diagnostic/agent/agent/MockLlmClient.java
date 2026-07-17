package com.diagnostic.agent.agent;

import com.diagnostic.agent.config.DiagnosticMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LLM Mock 实现，根据 systemPrompt 识别 Agent 领域，返回领域匹配的诊断结论。
 * Phase 1 默认激活，Phase 2 设置 provider=deepseek/openai 切换到真实 API。
 */
@Component
@ConditionalOnProperty(name = "diagnostic.llm.provider", havingValue = "mock", matchIfMissing = true)
public class MockLlmClient implements LlmClient {

    @Autowired(required = false)
    private DiagnosticMetrics metrics;

    private volatile LlmUsage lastUsage;

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        Timer.Sample sample = metrics != null ? metrics.startTimer() : null;
        try {
            // 优先使用 Tool 已生成的诊断结论
            String toolBased = buildToolBasedResponse(userPrompt);
            if (toolBased != null) {
                lastUsage = new LlmUsage(100, 50);
                return toolBased;
            }
            String domain = detectDomain(systemPrompt);
            String response = switch (domain) {
                case "CPU"    -> cpuResponse(userPrompt);
                case "MEMORY" -> memoryResponse(userPrompt);
                case "JVM"    -> jvmResponse(userPrompt);
                case "DISK"   -> diskResponse(userPrompt);
                case "SQL"    -> sqlResponse(userPrompt);
                default       -> genericResponse(userPrompt);
            };
            lastUsage = new LlmUsage(100, 50);
            return response;
        } finally {
            if (sample != null && metrics != null) {
                sample.stop(metrics.llmLatency("mock"));
                if (lastUsage != null) {
                    metrics.incrementTokens("mock", "prompt", lastUsage.promptTokens());
                    metrics.incrementTokens("mock", "completion", lastUsage.completionTokens());
                }
            }
        }
    }

    // ================================================================
    // Tool / Agent 诊断结论提取
    //
    // 背景：真实 LLM 会读取 userPrompt 中的 Tool 输出或 Agent 报告，
    // 在已有结论基础上生成自然语言摘要。Mock 模拟这一行为——不重新分析
    // 数据，只读取已生成的结论（Tool Summary / Agent Result Summary）。
    //
    // 依赖的格式标记全部来自代码中的固定字符串，不依赖自然语言 Prompt 内容：
    //   - "摘要: "            → BaseExpertAgent.formatToolResults() 固定前缀
    //   - "【" / "】"         → OrchestratorAgent.buildAgentReportsText() 固定格式
    //   - "风险等级: "        → DiagnosisResult.getRisk().name() 拼接
    //   - MEDIUM / HIGH       → RiskLevel 枚举值（Java enum）
    //
    // 注：这是 Mock 模式下模拟 LLM 聚合行为的兼容逻辑，
    //     不影响 OpenAiCompatibleLlmClient（完全独立的实现）。
    //     如果未来出现第三种 Prompt 格式，应重新评估设计而非继续追加分支。
    // ================================================================

    /**
     * 尝试从 userPrompt 中提取已有的诊断结论。
     * 按顺序尝试两种格式，命中其一即返回；均未命中返回 null，走关键词匹配。
     */
    private String buildToolBasedResponse(String userPrompt) {
        if (userPrompt == null) return null;
        String result = extractToolSummary(userPrompt);
        if (result != null) return result;
        return extractAgentReports(userPrompt);
    }

    /**
     * 格式1 — ExpertAgent 路径。
     * formatToolResults() 生成: "摘要: {Tool.buildSummary()}"
     * Tool.buildSummary() 输出包含风险等级（如 "检测到 2 个内存问题，风险等级 MEDIUM"）。
     */
    private String extractToolSummary(String userPrompt) {
        int idx = userPrompt.indexOf("摘要: ");
        if (idx < 0) return null;
        int end = userPrompt.indexOf("\n", idx);
        String summary = end > 0
                ? userPrompt.substring(idx + "摘要: ".length(), end).trim()
                : userPrompt.substring(idx + "摘要: ".length()).trim();
        if (summary.contains("风险等级 MEDIUM") || summary.contains("风险等级 HIGH")) {
            return summary;
        }
        return null;
    }

    /**
     * 格式2 — Orchestrator Summarizer 路径。
     * buildAgentReportsText() 生成:
     *   【{agentName}】风险等级: {RISK}
     *   {summary}
     *
     * 提取其中 MEDIUM / HIGH 的 Agent 摘要并拼接。
     */
    private String extractAgentReports(String userPrompt) {
        if (!userPrompt.contains("风险等级: MEDIUM") && !userPrompt.contains("风险等级: HIGH")) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        String riskPrefix = "风险等级: ";
        while ((pos = userPrompt.indexOf("【", pos)) >= 0) {
            int nameEnd = userPrompt.indexOf("】", pos);
            if (nameEnd < 0) break;
            String agentName = userPrompt.substring(pos + 1, nameEnd);
            int riskIdx = userPrompt.indexOf(riskPrefix, nameEnd);
            if (riskIdx < 0) break;
            int riskEnd = userPrompt.indexOf("\n", riskIdx);
            String risk = riskEnd > 0
                    ? userPrompt.substring(riskIdx + riskPrefix.length(), riskEnd).trim()
                    : userPrompt.substring(riskIdx + riskPrefix.length()).trim();
            if ("MEDIUM".equals(risk) || "HIGH".equals(risk)) {
                int summaryStart = riskEnd > 0 ? riskEnd + 1 : riskIdx + riskPrefix.length() + risk.length();
                int summaryEnd = findSummaryEnd(userPrompt, summaryStart);
                String agentSummary = userPrompt.substring(summaryStart, summaryEnd).trim();
                if (!agentSummary.isEmpty()) {
                    if (sb.length() > 0) sb.append("\n\n");
                    sb.append("【").append(agentName).append("】").append(agentSummary);
                }
            }
            pos = nameEnd + 1;
        }
        return sb.length() > 0 ? sb.toString().trim() : null;
    }

    private static int findSummaryEnd(String userPrompt, int from) {
        int end = userPrompt.indexOf("\n\n", from);
        if (end >= 0) return end;
        end = userPrompt.indexOf("【", from);
        if (end >= 0) return end;
        return userPrompt.length();
    }

    @Override
    public LlmUsage lastUsage() {
        return lastUsage;
    }

    // ---- 领域识别：基于 systemPrompt 内容 ----

    private String detectDomain(String systemPrompt) {
        if (systemPrompt == null) return "GENERIC";
        String s = systemPrompt;
        if (s.contains("CPU") || s.contains("系统性能优化")) return "CPU";
        if (s.contains("内存") || s.contains("数据库内存优化")) return "MEMORY";
        if (s.contains("JVM") || s.contains("JVM性能调优")) return "JVM";
        if (s.contains("磁盘") || s.contains("数据库存储优化")) return "DISK";
        if (s.contains("SQL") || s.contains("数据库性能优化")) return "SQL";
        return "GENERIC";
    }

    // ---- 各领域 Mock 响应 ----

    private String cpuResponse(String userPrompt) {
        String lower = userPrompt.toLowerCase();
        if (lower.contains("system cpu") && (lower.contains("high") || lower.contains("饱和"))) {
            return "系统 CPU 接近饱和，建议排查高 CPU 进程（可能是全表扫描或频繁 Full GC），"
                    + "考虑扩容或限制并发连接数。";
        }
        if (lower.contains("load avg") && lower.contains("超标")) {
            return "系统负载严重超标，load average 远超 CPU 核心数，"
                    + "建议立即排查高负载进程，检查是否有慢查询或死循环。";
        }
        if (lower.contains("processcpu") && lower.contains("high")) {
            return "当前 JVM 进程 CPU 占用过高，建议分析线程堆栈，"
                    + "检查是否有死循环、频繁 Full GC 或过度的正则匹配。";
        }
        if (lower.contains("cpu") && (lower.contains("偏高") || lower.contains("medium"))) {
            return "CPU 使用率偏高，建议关注趋势，检查是否有慢查询或频繁 GC 导致 CPU 波动。";
        }
        return "CPU 资源使用正常，未发现明显的 CPU 瓶颈。";
    }

    private String memoryResponse(String userPrompt) {
        String lower = userPrompt.toLowerCase();
        if (lower.contains("缓存命中率") && (lower.contains("严重过低") || lower.contains("high"))) {
            return "数据库缓存命中率严重过低，建议增大 shared_buffers 配置"
                    + "（通常设为系统内存的 25%），并检查是否有大量顺序扫描导致缓存失效。";
        }
        if (lower.contains("缓存命中率") && lower.contains("偏低")) {
            return "数据库缓存命中率偏低，建议关注趋势，评估是否需要增大 shared_buffers，"
                    + "同时检查是否有异常的全表扫描操作。";
        }
        if (lower.contains("临时文件") && lower.contains("过多")) {
            return "临时文件使用过多，可能因为 work_mem 配置偏小导致排序溢出到磁盘，"
                    + "建议适当增大 work_mem 并检查涉及 ORDER BY / DISTINCT 的查询。";
        }
        if (lower.contains("shared_buffers") && lower.contains("过低")) {
            return "shared_buffers 配置偏低，建议增大为系统内存的 25% 左右，"
                    + "以提高缓存命中率、减少磁盘 I/O。";
        }
        if (lower.contains("work_mem") && lower.contains("过高")) {
            return "work_mem 配置偏高，高并发排序查询时可能耗尽系统内存，"
                    + "建议根据并发量评估后适当调低。";
        }
        return "内存配置及缓存命中率正常，未发现明显的内存瓶颈。";
    }

    private String jvmResponse(String userPrompt) {
        String lower = userPrompt.toLowerCase();
        if (lower.contains("堆内存") && lower.contains("耗尽")) {
            return "堆内存接近耗尽，建议排查内存泄漏（如未关闭的 Connection、大量缓存对象），"
                    + "或增大 -Xmx 参数。建议结合 heap dump 进一步分析。";
        }
        if (lower.contains("堆内存") && lower.contains("偏高")) {
            return "堆内存使用率偏高，建议关注 GC 频率和内存增长趋势，"
                    + "排查是否有大对象频繁分配或缓存未设置过期时间。";
        }
        if (lower.contains("metaspace") && lower.contains("high")) {
            return "Metaspace (非堆内存) 使用率过高，可能由类加载泄漏导致，"
                    + "建议检查是否频繁创建动态代理或 CGLIB 增强类，考虑增大 -XX:MaxMetaspaceSize。";
        }
        if (lower.contains("线程数") && lower.contains("过多")) {
            return "JVM 线程数超过阈值，可能存在线程泄漏（如未关闭的线程池），"
                    + "建议排查线程堆栈，检查线程池配置是否合理。";
        }
        return "JVM 资源使用正常，堆内存、GC 活动和线程数均在合理范围。";
    }

    private String diskResponse(String userPrompt) {
        String lower = userPrompt.toLowerCase();
        if (lower.contains("磁盘使用率") && (lower.contains("严重过高") || lower.contains("high"))) {
            return "数据目录磁盘使用率严重过高，有宕机风险。建议立即清理旧 WAL 归档、"
                    + "pg_log 日志和临时文件，或尽快扩容磁盘。";
        }
        if (lower.contains("磁盘使用率") && lower.contains("偏高")) {
            return "数据目录磁盘使用率偏高，建议关注增长趋势，"
                    + "检查是否有大量 WAL 积压或日志未轮转，规划扩容时间窗口。";
        }
        if (lower.contains("可用空间") && lower.contains("严重不足")) {
            return "数据目录可用空间严重不足，面临宕机风险，请立即排查大表空间占用，"
                    + "清理过期数据和 WAL，或紧急扩容。";
        }
        return "磁盘空间使用正常，未发现存储瓶颈。";
    }

    private String sqlResponse(String userPrompt) {
        String lower = userPrompt.toLowerCase();
        if (lower.contains("全表扫描") || (lower.contains("seq scan") || lower.contains("all") && lower.contains("scan"))) {
            return "检测到全表扫描，建议在 WHERE 过滤列上创建索引以提升查询效率，"
                    + "或考虑改写 SQL 利用已有索引。";
        }
        if (lower.contains("filesort") || lower.contains("文件排序") || lower.contains("sort")) {
            return "检测到排序操作使用了文件排序或 Sort 节点扫描大量行，"
                    + "建议为 ORDER BY 列添加复合索引以优化排序性能。";
        }
        if (lower.contains("慢查询") || lower.contains("slow")) {
            return "检测到慢查询，建议使用 EXPLAIN 分析执行计划，"
                    + "针对性创建索引或改写 SQL 以降低执行时间。";
        }
        return "未发现明显的 SQL 性能问题，查询执行计划正常。";
    }

    private String genericResponse(String userPrompt) {
        return "未发现明显数据库性能问题";
    }
}
