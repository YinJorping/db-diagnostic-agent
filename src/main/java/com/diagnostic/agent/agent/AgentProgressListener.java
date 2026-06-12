package com.diagnostic.agent.agent;

/**
 * Agent 执行进度回调。由 SSE / WebSocket / 审计日志等层实现，Orchestrator 调用。
 * 回调在 Agent 工作线程中执行，实现方需保证线程安全。
 */
public interface AgentProgressListener {

    void onAgentStart(String sessionId, String agentName);

    void onAgentResult(String sessionId, DiagnosisResult result);

    AgentProgressListener NOOP = new NoopAgentProgressListener();
}
