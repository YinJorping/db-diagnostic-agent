package com.diagnostic.agent.memory;

import java.util.List;

public interface ChatMemoryStore {

    void add(String sessionId, StoredMessage message);

    default void addAll(String sessionId, List<StoredMessage> messages) {
        for (StoredMessage message : messages) {
            add(sessionId, message);
        }
    }

    List<StoredMessage> get(String sessionId);

    void clear(String sessionId);
}
