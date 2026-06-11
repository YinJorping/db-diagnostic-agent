package com.diagnostic.agent.controller;

import java.time.Instant;

public record DiagnosisEvent(
        DiagnosisEventType type,
        String message,
        Object data,
        Instant timestamp) {

    public static DiagnosisEvent of(DiagnosisEventType type, String message) {
        return new DiagnosisEvent(type, message, null, Instant.now());
    }

    public static DiagnosisEvent of(DiagnosisEventType type, String message, Object data) {
        return new DiagnosisEvent(type, message, data, Instant.now());
    }
}
