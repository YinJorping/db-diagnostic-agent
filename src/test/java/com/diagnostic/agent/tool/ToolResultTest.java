package com.diagnostic.agent.tool;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolResultTest {

    @Test
    void successShouldSetAllFields() {
        var detail = Map.of("risk", "HIGH");
        ToolResult result = ToolResult.success("ExplainTool", "检测到全表扫描", detail, 15L);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSummary()).isEqualTo("检测到全表扫描");
        assertThat(result.getDetail()).isEqualTo(detail);
        assertThat(result.getExecutionTimeMs()).isEqualTo(15L);
        assertThat(result.getToolName()).isEqualTo("ExplainTool");
        assertThat(result.getError()).isNull();
        assertThat(result.getTimestamp()).isNotNull();
    }

    @Test
    void failureShouldSetErrorAndNullData() {
        ToolResult result = ToolResult.failure("ExplainTool", "仅支持 SELECT 语句");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getError()).isEqualTo("仅支持 SELECT 语句");
        assertThat(result.getSummary()).isNull();
        assertThat(result.getDetail()).isNull();
        assertThat(result.getToolName()).isEqualTo("ExplainTool");
    }

    @Test
    void failureExecutionTimeShouldBeZero() {
        ToolResult result = ToolResult.failure("X", "err");
        assertThat(result.getExecutionTimeMs()).isZero();
    }
}
