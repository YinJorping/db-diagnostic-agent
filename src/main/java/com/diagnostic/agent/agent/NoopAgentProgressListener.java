package com.diagnostic.agent.agent;

/**
 * AgentProgressListener 的空实现，用于 REST API 等不需要进度通知的路径。
 */
public class NoopAgentProgressListener implements AgentProgressListener {

    @Override
    public void onAgentStart(String sessionId, String agentName) {
        // noop
    }

    @Override
    public void onAgentResult(String sessionId, DiagnosisResult result) {
        // noop
    }
}
