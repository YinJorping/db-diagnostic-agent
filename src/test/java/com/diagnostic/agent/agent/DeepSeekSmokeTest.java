package com.diagnostic.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DeepSeek API Smoke Test — 实际调用模型，验证完整诊断链路。
 * 前置条件：环境变量 DEEPSEEK_API_KEY 已设置。
 * 结果写入 target/smoke-test-results.md。
 */
class DeepSeekSmokeTest {

    private static final Path RESULTS = Paths.get("target/smoke-test-results.md");
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void checkApiKey() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        assertThat(key)
                .withFailMessage("DEEPSEEK_API_KEY 环境变量未设置，请先 export DEEPSEEK_API_KEY=sk-xxx")
                .isNotBlank();
    }

    @Test
    void shouldReturnDiagnosticConclusionFromRealDeepSeek() throws IOException {
        LlmProperties props = new LlmProperties();
        props.setProvider("deepseek");
        props.setTimeout(Duration.ofSeconds(60));
        props.setTemperature(0.3);
        props.setMaxTokens(1024);
        props.setProviders(Map.of("deepseek",
                new LlmProperties.ProviderConfig(
                        System.getenv("DEEPSEEK_API_KEY"),
                        "deepseek-chat",
                        "https://api.deepseek.com/v1")));

        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(props, mapper);

        Instant start = Instant.now();
        String result = client.chat(
                "你是数据库诊断专家，负责分析慢SQL。请用中文回答，控制在200字以内。",
                "用户问题：SELECT * FROM orders WHERE status='pending' 执行很慢，可能是什么原因？\n请基于你的专业知识给出诊断结论。");
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(result).isNotBlank();

        String md = """
                # DeepSeek Smoke Test 结果

                **时间**: %s
                **Provider**: deepseek
                **Model**: deepseek-chat
                **Temperature**: 0.3
                **Max Tokens**: 1024

                ## 延迟
                %dms

                ## LLM 输出
                %s
                """.formatted(
                DateTimeFormatter.ISO_INSTANT.format(start),
                elapsed.toMillis(),
                result);

        Files.createDirectories(RESULTS.getParent());
        Files.writeString(RESULTS, md);

        System.out.println("=== Smoke Test PASSED ===");
        System.out.println("Latency: " + elapsed.toMillis() + "ms");
        System.out.println("Result: " + result);
    }
}
