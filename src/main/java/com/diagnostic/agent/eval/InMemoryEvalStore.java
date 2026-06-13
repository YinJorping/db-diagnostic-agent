package com.diagnostic.agent.eval;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class InMemoryEvalStore {

    private final ConcurrentHashMap<String, EvalRun> store = new ConcurrentHashMap<>();

    public void save(EvalRun run) {
        store.put(run.getRunId(), run);
    }

    public EvalRun findByRunId(String runId) {
        return store.get(runId);
    }

    public List<EvalRun> findAll() {
        return new ArrayList<>(store.values());
    }
}
