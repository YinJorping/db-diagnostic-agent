package com.diagnostic.agent.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LlmPropertiesTest {

    @Test
    void shouldDefaultToMockProvider() {
        LlmProperties props = new LlmProperties();
        assertThat(props.getProvider()).isEqualTo("mock");
        assertThat(props.isMock()).isTrue();
    }

    @Test
    void shouldReturnActiveConfigForProvider() {
        LlmProperties props = new LlmProperties();
        props.setProvider("deepseek");
        props.setProviders(Map.of("deepseek",
                new LlmProperties.ProviderConfig("sk-xxx", "deepseek-chat", "https://api.deepseek.com/v1")));

        assertThat(props.isMock()).isFalse();
        assertThat(props.activeConfig()).isNotNull();
        assertThat(props.activeConfig().model()).isEqualTo("deepseek-chat");
        assertThat(props.activeConfig().baseUrl()).isEqualTo("https://api.deepseek.com/v1");
    }

    @Test
    void shouldReturnNullForMissingProvider() {
        LlmProperties props = new LlmProperties();
        props.setProvider("unknown");
        assertThat(props.activeConfig()).isNull();
    }

    @Test
    void shouldSupportMultipleProviders() {
        LlmProperties props = new LlmProperties();
        props.setProviders(Map.of(
                "deepseek", new LlmProperties.ProviderConfig("sk-d", "deepseek-chat", "https://api.deepseek.com/v1"),
                "openai", new LlmProperties.ProviderConfig("sk-o", "gpt-4o", "https://api.openai.com/v1")));

        assertThat(props.getProviders()).hasSize(2);
    }
}
