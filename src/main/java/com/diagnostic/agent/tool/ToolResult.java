package com.diagnostic.agent.tool;

import lombok.Getter;

import java.time.Instant;
import java.util.Map;

@Getter
public class ToolResult {

    private final boolean success;
    private final String summary;
    private final Object detail;
    private final long executionTimeMs;
    private final String toolName;
    private final String error;
    private final Instant timestamp;

    private ToolResult(boolean success, String summary, Object detail,
                       long executionTimeMs, String toolName, String error) {
        this.success = success;
        this.summary = summary;
        this.detail = detail;
        this.executionTimeMs = executionTimeMs;
        this.toolName = toolName;
        this.error = error;
        this.timestamp = Instant.now();
    }

    // ---- 成功工厂 ----

    public static ToolResult success(String toolName, String summary, Object detail, long executionTimeMs) {
        return new ToolResult(true, summary, detail, executionTimeMs, toolName, null);
    }

    // ---- 失败工厂 ----

    public static ToolResult failure(String toolName, String error) {
        return new ToolResult(false, null, null, 0, toolName, error);
    }
}
