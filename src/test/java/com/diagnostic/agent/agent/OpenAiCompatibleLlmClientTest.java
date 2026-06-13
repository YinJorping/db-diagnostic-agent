package com.diagnostic.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleLlmClientTest {

    private HttpServer server;
    private int port;
    private OpenAiCompatibleLlmClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.setExecutor(null);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private OpenAiCompatibleLlmClient createClient() {
        LlmProperties props = new LlmProperties();
        props.setProvider("deepseek");
        props.setProviders(Map.of("deepseek",
                new LlmProperties.ProviderConfig("sk-test", "deepseek-chat",
                        "http://localhost:" + port)));
        return new OpenAiCompatibleLlmClient(props, mapper);
    }

    @Test
    void shouldReturnContentFromValidResponse() {
        server.createContext("/chat/completions", exchange -> {
            String json = mapper.writeValueAsString(Map.of(
                    "id", "chatcmpl-123",
                    "object", "chat.completion",
                    "created", 1717200000,
                    "model", "deepseek-chat",
                    "choices", java.util.List.of(Map.of(
                            "index", 0,
                            "message", Map.of("role", "assistant", "content", "检测到全表扫描，建议加索引"),
                            "finish_reason", "stop"
                    )),
                    "usage", Map.of("prompt_tokens", 150, "completion_tokens", 30, "total_tokens", 180)
            ));
            byte[] bytes = json.getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        client = createClient();
        String result = client.chat("你是诊断专家", "SQL 很慢");

        assertThat(result).isEqualTo("检测到全表扫描，建议加索引");
    }

    @Test
    void shouldThrowOnNon200() {
        server.createContext("/chat/completions", exchange -> {
            String json = "{\"error\":{\"message\":\"Invalid API Key\"}}";
            byte[] bytes = json.getBytes();
            exchange.sendResponseHeaders(401, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });

        client = createClient();
        assertThatThrownBy(() -> client.chat("sys", "user"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("HTTP 401");
    }
}
