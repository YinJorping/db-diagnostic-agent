package com.diagnostic.agent.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class DiagnosticMetrics {

    private final MeterRegistry registry;

    public DiagnosticMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public Timer agentLatency(String agent) {
        return Timer.builder("agent.diagnosis.latency")
                .description("Agent diagnosis duration")
                .tag("agent", agent)
                .register(registry);
    }

    public Timer toolLatency(String tool) {
        return Timer.builder("tool.execution.latency")
                .description("Tool execution duration")
                .tag("tool", tool)
                .register(registry);
    }

    public void incrementToolFailure(String tool) {
        Counter.builder("tool.execution.failure")
                .description("Tool execution failure count")
                .tag("tool", tool)
                .register(registry)
                .increment();
    }

    public Timer llmLatency(String provider) {
        return Timer.builder("llm.call.latency")
                .description("LLM API call duration")
                .tag("provider", provider)
                .register(registry);
    }

    public void incrementLlmFailure(String provider) {
        Counter.builder("llm.call.failure")
                .description("LLM API call failure count")
                .tag("provider", provider)
                .register(registry)
                .increment();
    }

    public void incrementTokens(String provider, String type, int count) {
        Counter.builder("llm.tokens")
                .description("LLM token consumption")
                .tag("provider", provider)
                .tag("type", type)
                .register(registry)
                .increment(count);
    }

    public void incrementDiagnosis(String agent, String outcome) {
        Counter.builder("diagnosis.count")
                .description("Total diagnosis count")
                .tag("agent", agent)
                .tag("outcome", outcome)
                .register(registry)
                .increment();
    }
}
