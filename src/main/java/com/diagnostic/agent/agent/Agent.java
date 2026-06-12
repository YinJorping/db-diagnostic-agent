package com.diagnostic.agent.agent;

import java.util.List;

/**
 * Agent 统一协议。
 * 所有诊断专家 Agent 实现此接口，由 Orchestrator 统一调用。
 */
public interface Agent {

    String getName();

    String getDescription();

    DiagnosisResult diagnose(DiagnosisContext ctx);

    /**
     * 本 Agent 适用的主题关键词，Router 据此判断是否匹配。
     * 返回空列表表示不参与关键词路由。
     */
    default List<String> getKeywords() {
        return List.of();
    }
}
