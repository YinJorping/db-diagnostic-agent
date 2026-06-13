package com.diagnostic.agent.trace;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryExecutionTraceRepository implements ExecutionTraceRepository {

    private final ConcurrentHashMap<String, ExecutionTrace> store = new ConcurrentHashMap<>();

    @Override
    public void save(ExecutionTrace trace) {
        store.put(trace.getTraceId(), trace);
    }

    @Override
    public ExecutionTrace findByTraceId(String traceId) {
        return store.get(traceId);
    }

    @Override
    public List<ExecutionTrace> findBySessionId(String sessionId) {
        return store.values().stream()
                .filter(t -> sessionId.equals(t.getSessionId()))
                .toList();
    }
}
