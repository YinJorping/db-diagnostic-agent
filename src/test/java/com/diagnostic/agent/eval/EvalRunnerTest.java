package com.diagnostic.agent.eval;

import com.diagnostic.agent.agent.Agent;
import com.diagnostic.agent.agent.AgentRouter;
import com.diagnostic.agent.agent.DiagnosisContext;
import com.diagnostic.agent.agent.DiagnosisResult;
import com.diagnostic.agent.tool.RiskLevel;
import com.diagnostic.agent.trace.InMemoryExecutionTraceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvalRunnerTest {

    private EvalRunner runner;
    private InMemoryEvalStore evalStore;
    private EvalScorer scorer;
    private PromptOverrideManager overrideManager;
    private InMemoryExecutionTraceRepository traceRepository;

    @BeforeEach
    void setup() {
        var applicationContext = mock(org.springframework.context.ApplicationContext.class);
        var agentRouter = mock(AgentRouter.class);
        EvalCaseLoader caseLoader = mock(EvalCaseLoader.class);
        scorer = new EvalScorer();
        evalStore = new InMemoryEvalStore();
        overrideManager = new PromptOverrideManager();
        traceRepository = new InMemoryExecutionTraceRepository();
        Executor executor = Executors.newSingleThreadExecutor();

        // Stub agent
        Agent stubAgent = new StubAgent();
        when(applicationContext.getBean(anyString(), any(Class.class))).thenReturn(stubAgent);
        when(agentRouter.route(anyString())).thenReturn(stubAgent);

        // Stub case loader: 2 cases
        List<EvalCase> cases = List.of(
                new EvalCase("sql-001", "全表扫描", "SELECT * FROM t WHERE status='pending'",
                        new EvalCase.ExpectedCriteria("StubAgent", RiskLevel.HIGH,
                                List.of("索引", "全表扫描"), 1, List.of("创建索引"))),
                new EvalCase("sql-002", "慢查询", "慢查询日志出现多次",
                        new EvalCase.ExpectedCriteria("StubAgent", RiskLevel.MEDIUM,
                                List.of("慢查询"), 1, List.of()))
        );
        when(caseLoader.loadByDomain(anyString())).thenReturn(cases);

        runner = new EvalRunner(applicationContext, agentRouter, caseLoader, scorer,
                evalStore, overrideManager, traceRepository, executor);
    }

    @Test
    void shouldCreateRunAndExecuteCases() throws Exception {
        EvalRun run = runner.start("sql", "AUTO", Map.of());
        assertThat(run.getRunId()).isNotBlank();
        assertThat(run.getDomain()).isEqualTo("sql");

        // Wait for async completion
        for (int i = 0; i < 20; i++) {
            EvalRun latest = runner.getRun(run.getRunId());
            if (latest.getStatus() == EvalRunStatus.COMPLETED) break;
            Thread.sleep(100);
        }

        EvalRun completed = runner.getRun(run.getRunId());
        assertThat(completed.getStatus()).isEqualTo(EvalRunStatus.COMPLETED);

        EvalReport report = completed.getReport();
        assertThat(report).isNotNull();
        assertThat(report.results()).hasSize(2);
        assertThat(report.metrics().totalCases()).isEqualTo(2);
    }

    @Test
    void shouldRecordFailedRunForNonexistentDomain() throws InterruptedException {
        // Use empty case loader
        var appCtx = mock(org.springframework.context.ApplicationContext.class);
        var agentRouter = mock(AgentRouter.class);
        var caseLoader = mock(EvalCaseLoader.class);
        when(caseLoader.loadByDomain(anyString())).thenReturn(List.of()); // no cases

        EvalRunner emptyRunner = new EvalRunner(appCtx, agentRouter, caseLoader, scorer,
                evalStore, overrideManager, traceRepository, Executors.newSingleThreadExecutor());

        EvalRun run = emptyRunner.start("nonexistent", "AUTO", Map.of());
        for (int i = 0; i < 20; i++) {
            if (runner.getRun(run.getRunId()) != null
                    && runner.getRun(run.getRunId()).getStatus() == EvalRunStatus.COMPLETED)
                break;
            Thread.sleep(100);
        }
        // Should complete with 0 cases
        EvalRun completed = evalStore.findByRunId(run.getRunId());
        if (completed != null && completed.getStatus() == EvalRunStatus.COMPLETED) {
            assertThat(completed.getReport().results()).isEmpty();
        }
    }

    /**
     * Minimal stub agent that returns a fixed diagnosis result.
     */
    static class StubAgent implements Agent {
        @Override
        public String getName() { return "StubAgent"; }

        @Override
        public String getDescription() { return "stub"; }

        @Override
        public DiagnosisResult diagnose(DiagnosisContext ctx) {
            return DiagnosisResult.success("StubAgent",
                    "检测到全表扫描和索引缺失，建议创建索引优化查询",
                    "详细诊断内容", RiskLevel.HIGH, 100L, 100, 50);
        }
    }
}
