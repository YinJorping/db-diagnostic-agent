package com.diagnostic.agent.trace;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionTraceTest {

    @Test
    void shouldCreateTraceWithRequiredFields() {
        String traceId = UUID.randomUUID().toString();
        ExecutionTrace trace = new ExecutionTrace(traceId, "TestAgent", "sess-1", 1000L);

        assertThat(trace.getTraceId()).isEqualTo(traceId);
        assertThat(trace.getAgentName()).isEqualTo("TestAgent");
        assertThat(trace.getSessionId()).isEqualTo("sess-1");
        assertThat(trace.getStartTimeMs()).isEqualTo(1000L);
        assertThat(trace.getToolCalls()).isEmpty();
        assertThat(trace.getLlmCalls()).isEmpty();
    }

    @Test
    void shouldRecordToolCall() {
        ExecutionTrace trace = new ExecutionTrace("id", "agent", "sess", 0);
        trace.addToolCall(new ExecutionTrace.ToolCallRecord(
                "ExplainTool", Map.of("sql", "SELECT 1"), "ok", 100L, true));

        assertThat(trace.getToolCalls()).hasSize(1);
        ExecutionTrace.ToolCallRecord tc = trace.getToolCalls().get(0);
        assertThat(tc.toolName()).isEqualTo("ExplainTool");
        assertThat(tc.inputParams()).containsEntry("sql", "SELECT 1");
        assertThat(tc.outputSummary()).isEqualTo("ok");
        assertThat(tc.durationMs()).isEqualTo(100L);
        assertThat(tc.success()).isTrue();
    }

    @Test
    void shouldRecordFailedToolCall() {
        ExecutionTrace trace = new ExecutionTrace("id", "agent", "sess", 0);
        trace.addToolCall(new ExecutionTrace.ToolCallRecord(
                "BadTool", Map.of(), "error", 50L, false));

        assertThat(trace.getToolCalls().get(0).success()).isFalse();
    }

    @Test
    void shouldRecordLlmCall() {
        ExecutionTrace trace = new ExecutionTrace("id", "agent", "sess", 0);
        trace.addLlmCall(new ExecutionTrace.LlmCallRecord(100, 50, 500L));

        assertThat(trace.getLlmCalls()).hasSize(1);
        ExecutionTrace.LlmCallRecord lc = trace.getLlmCalls().get(0);
        assertThat(lc.promptTokens()).isEqualTo(100);
        assertThat(lc.completionTokens()).isEqualTo(50);
        assertThat(lc.latencyMs()).isEqualTo(500L);
    }

    @Test
    void shouldCompleteTraceWithSuccess() {
        ExecutionTrace trace = new ExecutionTrace("id", "agent", "sess", 0);
        trace.complete("diagnosis result", true, 2000L);

        assertThat(trace.getConclusion()).isEqualTo("diagnosis result");
        assertThat(trace.isSuccess()).isTrue();
        assertThat(trace.getEndTimeMs()).isEqualTo(2000L);
    }

    @Test
    void shouldCompleteTraceWithFailure() {
        ExecutionTrace trace = new ExecutionTrace("id", "agent", "sess", 0);
        trace.complete("error msg", false, 500L);

        assertThat(trace.isSuccess()).isFalse();
    }

    @Test
    void shouldPreserveMultipleToolCallsInOrder() {
        ExecutionTrace trace = new ExecutionTrace("id", "agent", "sess", 0);
        trace.addToolCall(new ExecutionTrace.ToolCallRecord("t1", Map.of(), "", 1, true));
        trace.addToolCall(new ExecutionTrace.ToolCallRecord("t2", Map.of(), "", 2, true));

        assertThat(trace.getToolCalls()).extracting(ExecutionTrace.ToolCallRecord::toolName)
                .containsExactly("t1", "t2");
    }
}
