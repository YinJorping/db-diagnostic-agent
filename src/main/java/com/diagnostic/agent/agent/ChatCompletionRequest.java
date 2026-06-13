package com.diagnostic.agent.agent;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
record ChatCompletionRequest(
        String model,
        List<Message> messages,
        double temperature,
        @JsonProperty("max_tokens") int maxTokens,
        boolean stream
) {
    record Message(String role, String content) {}

    static ChatCompletionRequest of(String model, String systemPrompt, String userPrompt,
                                    double temperature, int maxTokens) {
        return new ChatCompletionRequest(
                model,
                List.of(
                        new Message("system", systemPrompt),
                        new Message("user", userPrompt)
                ),
                temperature,
                maxTokens,
                false
        );
    }
}
