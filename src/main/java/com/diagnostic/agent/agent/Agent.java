package com.diagnostic.agent.agent;

/**
 * Agent 统一协议。
 * 所有诊断专家 Agent 实现此接口，由 Orchestrator 统一调用。
 */
public interface Agent {

    String getName();

    String getDescription();

    DiagnosisResult diagnose(String problem);
}
