package com.diagnostic.agent.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnosticMetricsTest {

    private MeterRegistry registry;
    private DiagnosticMetrics metrics;

    @BeforeEach
    void setup() {
        registry = new SimpleMeterRegistry();
        metrics = new DiagnosticMetrics(registry);
    }

    @Test
    void shouldStartTimer() {
        Timer.Sample sample = metrics.startTimer();
        assertThat(sample).isNotNull();
        sample.stop(Timer.builder("test").register(registry));
    }

    @Test
    void shouldCreateAgentLatencyTimer() {
        Timer timer = metrics.agentLatency("TestAgent");
        assertThat(timer.getId().getName()).isEqualTo("agent.diagnosis.latency");
        assertThat(timer.getId().getTag("agent")).isEqualTo("TestAgent");
    }

    @Test
    void shouldCreateToolLatencyTimer() {
        Timer timer = metrics.toolLatency("ExplainTool");
        assertThat(timer.getId().getName()).isEqualTo("tool.execution.latency");
        assertThat(timer.getId().getTag("tool")).isEqualTo("ExplainTool");
    }

    @Test
    void shouldIncrementToolFailure() {
        metrics.incrementToolFailure("ExplainTool");
        double count = registry.get("tool.execution.failure")
                .tag("tool", "ExplainTool").counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void shouldCreateLlmLatencyTimer() {
        Timer timer = metrics.llmLatency("deepseek");
        assertThat(timer.getId().getName()).isEqualTo("llm.call.latency");
        assertThat(timer.getId().getTag("provider")).isEqualTo("deepseek");
    }

    @Test
    void shouldIncrementLlmFailure() {
        metrics.incrementLlmFailure("deepseek");
        double count = registry.get("llm.call.failure")
                .tag("provider", "deepseek").counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void shouldIncrementTokens() {
        metrics.incrementTokens("deepseek", "prompt", 100);
        metrics.incrementTokens("deepseek", "completion", 50);
        double promptTokens = registry.get("llm.tokens")
                .tag("provider", "deepseek").tag("type", "prompt").counter().count();
        double completionTokens = registry.get("llm.tokens")
                .tag("provider", "deepseek").tag("type", "completion").counter().count();
        assertThat(promptTokens).isEqualTo(100.0);
        assertThat(completionTokens).isEqualTo(50.0);
    }

    @Test
    void shouldIncrementDiagnosis() {
        metrics.incrementDiagnosis("TestAgent", "success");
        double count = registry.get("diagnosis.count")
                .tag("agent", "TestAgent").tag("outcome", "success").counter().count();
        assertThat(count).isEqualTo(1.0);
    }
}
