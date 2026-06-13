package com.diagnostic.agent.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmHealthIndicatorTest {

    @Test
    void shouldBeUpForMockProvider() {
        LlmProperties props = new LlmProperties();
        var indicator = new LlmHealthIndicator(props);

        var health = indicator.health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("provider", "mock");
    }

    @Test
    void shouldBeDownWhenProviderConfigNotFound() {
        LlmProperties props = new LlmProperties();
        props.setProvider("openai");
        var indicator = new LlmHealthIndicator(props);

        var health = indicator.health();
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void shouldBeDownWhenApiKeyIsBlank() {
        LlmProperties props = new LlmProperties();
        props.setProvider("deepseek");
        props.setProviders(Map.of("deepseek",
                new LlmProperties.ProviderConfig("", "deepseek-chat", "https://api.deepseek.com/v1")));
        var indicator = new LlmHealthIndicator(props);

        var health = indicator.health();
        assertThat(health.getStatus().getCode()).isEqualTo("DOWN");
    }

    @Test
    void shouldBeUpWhenApiKeyIsConfigured() {
        LlmProperties props = new LlmProperties();
        props.setProvider("deepseek");
        props.setProviders(Map.of("deepseek",
                new LlmProperties.ProviderConfig("sk-xxx", "deepseek-chat", "https://api.deepseek.com/v1")));
        var indicator = new LlmHealthIndicator(props);

        var health = indicator.health();
        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat(health.getDetails()).containsEntry("model", "deepseek-chat");
    }
}
