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
