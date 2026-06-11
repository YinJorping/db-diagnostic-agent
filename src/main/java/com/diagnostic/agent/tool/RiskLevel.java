package com.diagnostic.agent.tool;

/**
 * 工具执行风险等级。
 * UNKNOWN 用于失败场景，避免 null。
 */
public enum RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    UNKNOWN
}
