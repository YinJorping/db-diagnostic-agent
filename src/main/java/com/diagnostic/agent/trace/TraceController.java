package com.diagnostic.agent.trace;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class TraceController {

    private final ExecutionTraceRepository repository;

    public TraceController(ExecutionTraceRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/api/traces")
    public Map<String, Object> getTrace(
            @RequestParam(required = false) String traceId,
            @RequestParam(required = false) String sessionId) {
        if (traceId != null) {
            ExecutionTrace trace = repository.findByTraceId(traceId);
            return trace != null ? Map.of("found", true, "trace", trace)
                    : Map.of("found", false, "message", "trace not found: " + traceId);
        }
        if (sessionId != null) {
            List<ExecutionTrace> traces = repository.findBySessionId(sessionId);
            return Map.of("found", !traces.isEmpty(), "sessionId", sessionId,
                    "count", traces.size(), "traces", traces);
        }
        return Map.of("found", false, "message", "provide traceId or sessionId");
    }
}
