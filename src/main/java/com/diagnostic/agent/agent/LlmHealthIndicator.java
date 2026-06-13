package com.diagnostic.agent.agent;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class LlmHealthIndicator implements HealthIndicator {

    private final LlmProperties llmProps;

    public LlmHealthIndicator(LlmProperties llmProps) {
        this.llmProps = llmProps;
    }

    @Override
    public Health health() {
        if (llmProps.isMock()) {
            return Health.up()
                    .withDetail("provider", "mock")
                    .withDetail("note", "使用 Mock 模式，无外部 API 依赖")
                    .build();
        }

        LlmProperties.ProviderConfig config = llmProps.activeConfig();
        if (config == null) {
            return Health.down()
                    .withDetail("provider", llmProps.getProvider())
                    .withDetail("reason", "未找到 provider 配置，检查 diagnostic.llm.providers." + llmProps.getProvider())
                    .build();
        }

        if (config.apiKey() == null || config.apiKey().isBlank()) {
            return Health.down()
                    .withDetail("provider", llmProps.getProvider())
                    .withDetail("model", config.model())
                    .withDetail("reason", "API Key 未配置，检查环境变量")
                    .build();
        }

        return Health.up()
                .withDetail("provider", llmProps.getProvider())
                .withDetail("model", config.model())
                .withDetail("baseUrl", config.baseUrl())
                .build();
    }
}
