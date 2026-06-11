package com.diagnostic.agent.memory;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Profile({"dev", "test"})
public class InMemoryChatMemoryStore implements ChatMemoryStore {

    private final ConcurrentHashMap<String, List<StoredMessage>> store = new ConcurrentHashMap<>();

    @Override
    public void add(String sessionId, StoredMessage message) {
        store.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(message);
    }

    @Override
    public List<StoredMessage> get(String sessionId) {
        return new ArrayList<>(store.getOrDefault(sessionId, List.of()));
    }

    @Override
    public void clear(String sessionId) {
        store.remove(sessionId);
    }
}
