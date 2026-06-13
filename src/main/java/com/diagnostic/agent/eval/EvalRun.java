package com.diagnostic.agent.eval;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public class EvalRun {

    private final String runId;
    private final String domain;
    private final String mode;
    private final Map<String, String> promptOverrides;
    private volatile EvalRunStatus status;
    private volatile EvalReport report;
    private final Instant startTime;
    private volatile Instant endTime;

    public EvalRun(String domain, String mode, Map<String, String> promptOverrides) {
        this.runId = UUID.randomUUID().toString().substring(0, 8);
        this.domain = domain;
        this.mode = mode;
        this.promptOverrides = promptOverrides;
        this.status = EvalRunStatus.PENDING;
        this.startTime = Instant.now();
    }

    public String getRunId() { return runId; }
    public String getDomain() { return domain; }
    public String getMode() { return mode; }
    public Map<String, String> getPromptOverrides() { return promptOverrides; }
    public EvalRunStatus getStatus() { return status; }
    public EvalReport getReport() { return report; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }

    public void setRunning() { this.status = EvalRunStatus.RUNNING; }
    public void setCompleted(EvalReport report) {
        this.report = report;
        this.status = EvalRunStatus.COMPLETED;
        this.endTime = Instant.now();
    }
    public void setFailed() {
        this.status = EvalRunStatus.FAILED;
        this.endTime = Instant.now();
    }
}
