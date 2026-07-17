package com.diagnostic.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
@Profile("!dev & !test")
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryStore.class);
    private static final String KEY_PREFIX = "chat:memory:";
    private static final Duration TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatMemoryStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    }

    @Override
    public void add(String sessionId, StoredMessage message) {
        String key = key(sessionId);
        List<StoredMessage> existing = get(sessionId);
        existing.add(message);
        String json = serialize(existing);
        redisTemplate.opsForValue().set(key, json, TTL);
    }

    @Override
    public void addAll(String sessionId, List<StoredMessage> messages) {
        if (messages.isEmpty()) return;
        String key = key(sessionId);
        List<StoredMessage> existing = get(sessionId);
        existing.addAll(messages);
        String json = serialize(existing);
        redisTemplate.opsForValue().set(key, json, TTL);
    }

    @Override
    public List<StoredMessage> get(String sessionId) {
        String key = key(sessionId);
        String json = redisTemplate.opsForValue().get(key);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        return deserialize(json);
    }

    @Override
    public void clear(String sessionId) {
        redisTemplate.delete(key(sessionId));
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private String serialize(List<StoredMessage> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化消息失败", e);
        }
    }

    private List<StoredMessage> deserialize(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<StoredMessage>>() {});
        } catch (JsonProcessingException e) {
            log.warn("反序列化消息失败，返回空列表: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
