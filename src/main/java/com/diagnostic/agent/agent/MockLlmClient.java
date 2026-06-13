package com.diagnostic.agent.agent;

import com.diagnostic.agent.config.DiagnosticMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * LLM Mock 实现，关键词匹配返回预设诊断结论。
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
        String provider = "mock";
        try {
            String lower = userPrompt.toLowerCase();

            int promptTokens = 100;
            int completionTokens = 50;

            // ExplainTool 检测到全表扫描
            if (lower.contains("全表扫描") || lower.contains("all") && lower.contains("scan")) {
                lastUsage = new LlmUsage(promptTokens, completionTokens);
                return "检测到全表扫描，建议在相关字段上创建索引以提升查询效率";
            }

            // ExplainTool 检测到文件排序
            if (lower.contains("filesort") || lower.contains("文件排序")) {
                lastUsage = new LlmUsage(promptTokens, completionTokens);
                return "检测到 Using filesort，建议优化 ORDER BY 子句或添加复合索引避免文件排序";
            }

            // SlowQueryTool 返回慢查询
            if (lower.contains("慢查询") || lower.contains("slow_query")) {
                lastUsage = new LlmUsage(promptTokens, completionTokens);
                return "检测到慢查询，建议分析执行计划并考虑索引优化或 SQL 改写";
            }

            // CpuLoadTool 返回高负载
            if (lower.contains("cpu") && (lower.contains("high") || lower.contains("高"))) {
                lastUsage = new LlmUsage(promptTokens, completionTokens);
                return "检测到 CPU 使用率偏高，可能与全表扫描或高频慢查询有关，建议结合诊断工具进一步排查";
            }

            // 无异常
            lastUsage = new LlmUsage(80, 30);
            return "未发现明显数据库性能问题";
        } finally {
            if (sample != null && metrics != null) {
                sample.stop(metrics.llmLatency(provider));
                if (lastUsage != null) {
                    metrics.incrementTokens(provider, "prompt", lastUsage.promptTokens());
                    metrics.incrementTokens(provider, "completion", lastUsage.completionTokens());
                }
            }
        }
    }

    @Override
    public LlmUsage lastUsage() {
        return lastUsage;
    }
}
