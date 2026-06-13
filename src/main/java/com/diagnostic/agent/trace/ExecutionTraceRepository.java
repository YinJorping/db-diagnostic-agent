package com.diagnostic.agent.trace;

import java.util.List;

public interface ExecutionTraceRepository {

    void save(ExecutionTrace trace);

    ExecutionTrace findByTraceId(String traceId);

    List<ExecutionTrace> findBySessionId(String sessionId);
}
