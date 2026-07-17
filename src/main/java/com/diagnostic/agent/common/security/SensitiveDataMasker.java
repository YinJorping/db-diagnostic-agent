package com.diagnostic.agent.common.security;

/**
 * 敏感数据脱敏器——在数据发送给外部 LLM 前进行脱敏处理。
 * 当前实现为基于正则表达式的轻量方案，后续可按需扩展为策略模式。
 */
@FunctionalInterface
public interface SensitiveDataMasker {

    /** 对输入文本进行脱敏，返回脱敏后的文本。null 安全。 */
    String mask(String content);
}
