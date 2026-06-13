package com.diagnostic.agent.agent;

import com.diagnostic.agent.config.DiagnosticMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
@ConditionalOnExpression("!'${diagnostic.llm.provider:mock}'.equals('mock')")
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    private final LlmProperties llmProps;
    private final LlmProperties.ProviderConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final DiagnosticMetrics metrics;
    private volatile LlmUsage lastUsage;

    public OpenAiCompatibleLlmClient(LlmProperties llmProps, ObjectMapper mapper,
                                     DiagnosticMetrics metrics) {
        this.llmProps = llmProps;
        this.config = llmProps.activeConfig();
        this.mapper = mapper;
        this.metrics = metrics;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(llmProps.getTimeout())
                .build();
        log.info("LLM 客户端初始化: provider={}, model={}, baseUrl={}",
                llmProps.getProvider(), config.model(), config.baseUrl());
    }

    @Override
    public LlmUsage lastUsage() {
        return lastUsage;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        ChatCompletionRequest request = ChatCompletionRequest.of(
                config.model(), systemPrompt, userPrompt,
                llmProps.getTemperature(), llmProps.getMaxTokens());

        Timer.Sample sample = metrics.startTimer();
        long start = System.currentTimeMillis();
        String provider = llmProps.getProvider();
        try {
            String body = mapper.writeValueAsString(request);
            log.debug("LLM 请求体: {}", body);

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(config.baseUrl() + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(llmProps.getTimeout())
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            long elapsed = System.currentTimeMillis() - start;

            if (response.statusCode() != 200) {
                log.error("LLM API 返回非 200: status={}, body={}", response.statusCode(), response.body());
                metrics.incrementLlmFailure(provider);
                sample.stop(metrics.llmLatency(provider));
                throw new RuntimeException("LLM API 调用失败: HTTP " + response.statusCode());
            }

            ChatCompletionResponse cr = mapper.readValue(response.body(), ChatCompletionResponse.class);
            String content = cr.content();

            if (cr.usage() != null) {
                lastUsage = new LlmUsage(cr.usage().promptTokens(), cr.usage().completionTokens());
                metrics.incrementTokens(provider, "prompt", cr.usage().promptTokens());
                metrics.incrementTokens(provider, "completion", cr.usage().completionTokens());
            }

            sample.stop(metrics.llmLatency(provider));

            if (content == null) {
                log.warn("LLM 返回空内容: response={}", response.body());
                return "未获取到诊断结论";
            }

            log.info("LLM 调用完成: provider={}, model={}, tokens={} (prompt={}+completion={}), latencyMs={}",
                    provider, config.model(),
                    cr.usage() != null ? cr.usage().totalTokens() : -1,
                    cr.usage() != null ? cr.usage().promptTokens() : -1,
                    cr.usage() != null ? cr.usage().completionTokens() : -1,
                    elapsed);

            return content;
        } catch (IOException e) {
            metrics.incrementLlmFailure(provider);
            sample.stop(metrics.llmLatency(provider));
            log.error("LLM API 网络异常: provider={}, model={}, latencyMs={}",
                    provider, config.model(), System.currentTimeMillis() - start, e);
            throw new RuntimeException("LLM API 调用失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            metrics.incrementLlmFailure(provider);
            sample.stop(metrics.llmLatency(provider));
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM API 调用被中断", e);
        }
    }
}
