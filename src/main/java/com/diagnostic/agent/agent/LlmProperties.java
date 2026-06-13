package com.diagnostic.agent.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "diagnostic.llm")
public class LlmProperties {

    private String provider = "mock";
    private Duration timeout = Duration.ofSeconds(30);
    private double temperature = 0.3;
    private int maxTokens = 1024;
    private Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public Map<String, ProviderConfig> getProviders() { return providers; }
    public void setProviders(Map<String, ProviderConfig> providers) { this.providers = providers; }

    public ProviderConfig activeConfig() {
        return providers.get(provider);
    }

    public boolean isMock() {
        return "mock".equals(provider);
    }

    public record ProviderConfig(String apiKey, String model, String baseUrl) {}
}
