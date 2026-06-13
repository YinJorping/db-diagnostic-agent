package com.diagnostic.agent.eval;

import com.diagnostic.agent.agent.Agent;
import com.diagnostic.agent.agent.AgentRouter;
import com.diagnostic.agent.agent.DiagnosisContext;
import com.diagnostic.agent.agent.DiagnosisResult;
import com.diagnostic.agent.agent.LlmClient;
import com.diagnostic.agent.trace.ExecutionTrace;
import com.diagnostic.agent.trace.ExecutionTraceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

@Component
public class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final ApplicationContext applicationContext;
    private final AgentRouter agentRouter;
    private final EvalCaseLoader caseLoader;
    private final EvalScorer scorer;
    private final InMemoryEvalStore evalStore;
    private final PromptOverrideManager overrideManager;
    private final ExecutionTraceRepository traceRepository;
    private final Executor executor;

    public EvalRunner(ApplicationContext applicationContext,
                      AgentRouter agentRouter,
                      EvalCaseLoader caseLoader,
                      EvalScorer scorer,
                      InMemoryEvalStore evalStore,
                      PromptOverrideManager overrideManager,
                      ExecutionTraceRepository traceRepository,
                      @Qualifier("agentExecutor") Executor executor) {
        this.applicationContext = applicationContext;
        this.agentRouter = agentRouter;
        this.caseLoader = caseLoader;
        this.scorer = scorer;
        this.evalStore = evalStore;
        this.overrideManager = overrideManager;
        this.traceRepository = traceRepository;
        this.executor = executor;
    }

    public EvalRun start(String domain, String mode, Map<String, String> promptOverrides) {
        EvalRun run = new EvalRun(domain, mode, promptOverrides);
        evalStore.save(run);

        executor.execute(() -> executeRun(run));
        return run;
    }

    private void executeRun(EvalRun run) {
        run.setRunning();
        List<EvalResult> results = new ArrayList<>();
        try {
            overrideManager.set(run.getPromptOverrides());

            List<EvalCase> cases = caseLoader.loadByDomain(run.getDomain());
            log.info("Eval run {} started: domain={}, cases={}", run.getRunId(), run.getDomain(), cases.size());

            for (EvalCase evalCase : cases) {
                try {
                    EvalResult result = executeCase(evalCase, run.getMode());
                    results.add(result);
                } catch (Exception e) {
                    log.warn("Eval case {} failed: {}", evalCase.id(), e.getMessage());
                    results.add(failureResult(evalCase, e.getMessage()));
                }
            }

            EvalReport.EvalMetrics metrics = scorer.computeMetrics(results);
            String variantDesc = run.getPromptOverrides().isEmpty() ? "baseline" : "experimental";
            EvalReport report = new EvalReport(run.getRunId(), run.getDomain(), variantDesc,
                    run.getStartTime(), Instant.now(), EvalRunStatus.COMPLETED, results, metrics);
            run.setCompleted(report);
            log.info("Eval run {} completed: agentAcc={}, riskAcc={}, kwCov={}, recCov={}",
                    run.getRunId(), metrics.agentAccuracy(), metrics.riskAccuracy(),
                    metrics.keywordCoverage(), metrics.recommendationCoverage());
        } catch (Exception e) {
            log.error("Eval run {} failed", run.getRunId(), e);
            run.setFailed();
        } finally {
            overrideManager.clear();
        }
    }

    private EvalResult executeCase(EvalCase evalCase, String mode) {
        Agent agent = resolveAgent(evalCase, mode);
        if (agent == null) {
            return new EvalResult(evalCase.id(), evalCase.description(),
                    "NONE", com.diagnostic.agent.tool.RiskLevel.UNKNOWN,
                    null, 0, 0, 0,
                    false, false, 0.0, 0.0,
                    false, "No agent routed for: " + evalCase.problem());
        }

        String sessionId = "eval-" + evalCase.id();
        DiagnosisContext ctx = new DiagnosisContext(sessionId, evalCase.problem());

        long t0 = System.currentTimeMillis();
        DiagnosisResult diagResult = agent.diagnose(ctx);
        long latency = System.currentTimeMillis() - t0;

        int promptTokens = 0;
        int completionTokens = 0;
        List<ExecutionTrace> traces = traceRepository.findBySessionId(sessionId);
        if (traces != null && !traces.isEmpty()) {
            ExecutionTrace trace = traces.get(traces.size() - 1);
            if (!trace.getLlmCalls().isEmpty()) {
                ExecutionTrace.LlmCallRecord lastLlm = trace.getLlmCalls().get(trace.getLlmCalls().size() - 1);
                promptTokens = lastLlm.promptTokens();
                completionTokens = lastLlm.completionTokens();
            }
        }

        return scorer.evaluate(evalCase, diagResult, promptTokens, completionTokens, latency);
    }

    private Agent resolveAgent(EvalCase evalCase, String mode) {
        if ("DIRECT".equalsIgnoreCase(mode)) {
            return applicationContext.getBean(evalCase.expected().agent(), Agent.class);
        }
        return agentRouter.route(evalCase.problem());
    }

    private EvalResult failureResult(EvalCase evalCase, String error) {
        return new EvalResult(evalCase.id(), evalCase.description(),
                "NONE", com.diagnostic.agent.tool.RiskLevel.UNKNOWN,
                null, 0, 0, 0,
                false, false, 0.0, 0.0,
                false, error);
    }

    public EvalRun getRun(String runId) {
        return evalStore.findByRunId(runId);
    }
}
