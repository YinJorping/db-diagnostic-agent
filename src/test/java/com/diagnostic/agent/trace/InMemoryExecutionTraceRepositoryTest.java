package com.diagnostic.agent.trace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryExecutionTraceRepositoryTest {

    private InMemoryExecutionTraceRepository repository;

    @BeforeEach
    void setup() {
        repository = new InMemoryExecutionTraceRepository();
    }

    @Test
    void shouldSaveAndFindByTraceId() {
        String traceId = UUID.randomUUID().toString();
        ExecutionTrace trace = new ExecutionTrace(traceId, "TestAgent", "sess-1", 0);
        repository.save(trace);

        ExecutionTrace found = repository.findByTraceId(traceId);
        assertThat(found).isNotNull();
        assertThat(found.getTraceId()).isEqualTo(traceId);
    }

    @Test
    void shouldReturnNullForUnknownTraceId() {
        assertThat(repository.findByTraceId("nonexistent")).isNull();
    }

    @Test
    void shouldFindBySessionId() {
        String sessId = "sess-shared";
        repository.save(new ExecutionTrace(UUID.randomUUID().toString(), "Agent1", sessId, 0));
        repository.save(new ExecutionTrace(UUID.randomUUID().toString(), "Agent2", sessId, 0));
        repository.save(new ExecutionTrace(UUID.randomUUID().toString(), "Agent3", "other-sess", 0));

        List<ExecutionTrace> results = repository.findBySessionId(sessId);
        assertThat(results).hasSize(2);
        assertThat(results).extracting(ExecutionTrace::getAgentName)
                .containsExactlyInAnyOrder("Agent1", "Agent2");
    }

    @Test
    void shouldReturnEmptyListForUnknownSessionId() {
        List<ExecutionTrace> results = repository.findBySessionId("nonexistent");
        assertThat(results).isEmpty();
    }

    @Test
    void shouldOverwriteBySameTraceId() {
        String traceId = UUID.randomUUID().toString();
        ExecutionTrace t1 = new ExecutionTrace(traceId, "Agent1", "sess-1", 0);
        ExecutionTrace t2 = new ExecutionTrace(traceId, "Agent2", "sess-2", 0);
        repository.save(t1);
        repository.save(t2);

        assertThat(repository.findByTraceId(traceId).getAgentName()).isEqualTo("Agent2");
    }
}
