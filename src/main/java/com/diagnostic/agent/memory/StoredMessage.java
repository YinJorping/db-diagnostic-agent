package com.diagnostic.agent.memory;

import java.time.Instant;

public record StoredMessage(
        MessageType type,
        String text,
        Instant timestamp) {

    public static StoredMessage of(MessageType type, String text) {
        return new StoredMessage(type, text, Instant.now());
    }
}
