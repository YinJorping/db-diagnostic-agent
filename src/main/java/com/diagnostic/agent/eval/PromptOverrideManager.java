package com.diagnostic.agent.eval;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PromptOverrideManager {

    private final ThreadLocal<Map<String, String>> overrides = new ThreadLocal<>();

    public String get(String templateKey) {
        Map<String, String> map = overrides.get();
        return map != null ? map.get(templateKey) : null;
    }

    public void set(Map<String, String> overrides) {
        this.overrides.set(overrides);
    }

    public void clear() {
        overrides.remove();
    }
}
