package com.diagnostic.agent.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ExecutionTrace {

    private final String traceId;
    private final String agentName;
    private final String sessionId;
    private final long startTimeMs;
    private long endTimeMs;
    private final List<ToolCallRecord> toolCalls = new ArrayList<>();
    private final List<LlmCallRecord> llmCalls = new ArrayList<>();
    private String conclusion;
    private boolean success;

    public ExecutionTrace(String traceId, String agentName, String sessionId, long startTimeMs) {
        this.traceId = traceId;
        this.agentName = agentName;
        this.sessionId = sessionId;
        this.startTimeMs = startTimeMs;
    }

    public void addToolCall(ToolCallRecord record) {
        toolCalls.add(record);
    }

    public void addLlmCall(LlmCallRecord record) {
        llmCalls.add(record);
    }

    public void complete(String conclusion, boolean success, long endTimeMs) {
        this.conclusion = conclusion;
        this.success = success;
        this.endTimeMs = endTimeMs;
    }

    public String getTraceId() { return traceId; }
    public String getAgentName() { return agentName; }
    public String getSessionId() { return sessionId; }
    public long getStartTimeMs() { return startTimeMs; }
    public long getEndTimeMs() { return endTimeMs; }
    public List<ToolCallRecord> getToolCalls() { return toolCalls; }
    public List<LlmCallRecord> getLlmCalls() { return llmCalls; }
    public String getConclusion() { return conclusion; }
    public boolean isSuccess() { return success; }

    public record ToolCallRecord(
            String toolName,
            Map<String, Object> inputParams,
            String outputSummary,
            long durationMs,
            boolean success) {}

    public record LlmCallRecord(
            int promptTokens,
            int completionTokens,
            long latencyMs) {}
}
