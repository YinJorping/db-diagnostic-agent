package com.diagnostic.agent.tool;

import java.util.Map;

/**
 * 诊断工具抽象接口。
 * 所有 Tool（ExplainTool, SlowQueryTool, …）实现此接口，
 * 由 ToolRegistry 自动发现并注册。
 */
public interface Tool {

    /** 工具唯一标识 */
    String getName();

    /** 工具功能描述（给 Agent 理解用途） */
    String getDescription();

    /** 执行工具，传入参数，返回结构化结果 */
    ToolResult execute(Map<String, Object> params);
}
