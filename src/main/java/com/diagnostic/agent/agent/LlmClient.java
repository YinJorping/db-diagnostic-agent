package com.diagnostic.agent.agent;

/**
 * LLM 客户端抽象接口。
 * Phase 1 使用 {@link MockLlmClient}，Phase 2 切换真实模型实现。
 * Agent 只依赖此接口，不耦合具体 LLM 框架。
 */
public interface LlmClient {

    /**
     * 发起对话
     * @param systemPrompt 系统提示词（角色设定）
     * @param userPrompt   用户输入（含诊断上下文 + tool 结果）
     * @return 模型回复文本
     */
    String chat(String systemPrompt, String userPrompt);
}
