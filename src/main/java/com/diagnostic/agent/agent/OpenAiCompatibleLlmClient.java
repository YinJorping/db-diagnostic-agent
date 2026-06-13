package com.diagnostic.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    public OpenAiCompatibleLlmClient(LlmProperties llmProps, ObjectMapper mapper) {
        this.llmProps = llmProps;
        this.config = llmProps.activeConfig();
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(llmProps.getTimeout())
                .build();
        log.info("LLM 客户端初始化: provider={}, model={}, baseUrl={}",
                llmProps.getProvider(), config.model(), config.baseUrl());
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        ChatCompletionRequest request = ChatCompletionRequest.of(
                config.model(), systemPrompt, userPrompt,
                llmProps.getTemperature(), llmProps.getMaxTokens());

        long start = System.currentTimeMillis();
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
                throw new RuntimeException("LLM API 调用失败: HTTP " + response.statusCode());
            }

            ChatCompletionResponse cr = mapper.readValue(response.body(), ChatCompletionResponse.class);
            String content = cr.content();

            if (content == null) {
                log.warn("LLM 返回空内容: response={}", response.body());
                return "未获取到诊断结论";
            }

            log.info("LLM 调用完成: provider={}, model={}, tokens={} (prompt={}+completion={}), latencyMs={}",
                    llmProps.getProvider(), config.model(),
                    cr.usage() != null ? cr.usage().totalTokens() : -1,
                    cr.usage() != null ? cr.usage().promptTokens() : -1,
                    cr.usage() != null ? cr.usage().completionTokens() : -1,
                    elapsed);

            return content;
        } catch (IOException e) {
            log.error("LLM API 网络异常: provider={}, model={}, latencyMs={}",
                    llmProps.getProvider(), config.model(), System.currentTimeMillis() - start, e);
            throw new RuntimeException("LLM API 调用失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("LLM API 调用被中断", e);
        }
    }
}
