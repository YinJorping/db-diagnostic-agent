package com.diagnostic.agent.agent;

import com.diagnostic.agent.memory.ChatMemoryStore;
import com.diagnostic.agent.memory.MessageType;
import com.diagnostic.agent.memory.StoredMessage;
import com.diagnostic.agent.repository.DiagnosisRecord;
import com.diagnostic.agent.repository.DiagnosisRecordRepository;
import com.diagnostic.agent.repository.Session;
import com.diagnostic.agent.repository.SessionRepository;
import com.diagnostic.agent.tool.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Component
public class OrchestratorAgent {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorAgent.class);
    private static final String GENERAL_AGENT = "GeneralAgent";
    private static final String FALLBACK_SYSTEM_PROMPT = "You are a helpful assistant.";

    private final SessionRepository sessionRepository;
    private final DiagnosisRecordRepository recordRepository;
    private final AgentRouter agentRouter;
    private final PromptService promptService;
    private final LlmClient llmClient;
    private final ChatMemoryStore memoryStore;
    private final PromptContextBuilder promptContextBuilder;
    private final Executor agentExecutor;

    public OrchestratorAgent(SessionRepository sessionRepository,
                             DiagnosisRecordRepository recordRepository,
                             AgentRouter agentRouter,
                             PromptService promptService,
                             LlmClient llmClient,
                             ChatMemoryStore memoryStore,
                             PromptContextBuilder promptContextBuilder,
                             @Qualifier("agentExecutor") Executor agentExecutor) {
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.agentRouter = agentRouter;
        this.promptService = promptService;
        this.llmClient = llmClient;
        this.memoryStore = memoryStore;
        this.promptContextBuilder = promptContextBuilder;
        this.agentExecutor = agentExecutor;
    }

    public DiagnosisReport diagnose(String sessionId, String problem) {
        return diagnose(sessionId, problem, AgentProgressListener.NOOP);
    }

    public DiagnosisReport diagnose(String sessionId, String problem, AgentProgressListener listener) {
        findOrCreateSession(sessionId);
        DiagnosisRecord record = createRecord(sessionId, problem);

        try {
            DiagnosisReport report = executeMultiDiagnosis(sessionId, problem, listener);
            completeRecord(record, report);
            memoryStore.addAll(sessionId, List.of(
                    StoredMessage.of(MessageType.USER, problem),
                    StoredMessage.of(MessageType.ASSISTANT, report.finalSummary())
            ));
            return report;
        } catch (Exception e) {
            log.error("诊断失败: sessionId={}, problem={}", sessionId, problem, e);
            failRecord(record, e.getMessage());
            throw e;
        }
    }

    // ---- 诊断执行 ----

    private DiagnosisReport executeMultiDiagnosis(String sessionId, String problem,
                                                   AgentProgressListener listener) {
        List<Agent> agents = agentRouter.routeAll(problem);
        if (agents.isEmpty()) {
            DiagnosisResult fallback = generalFallback(new DiagnosisContext(sessionId, problem));
            listener.onAgentStart(sessionId, GENERAL_AGENT);
            listener.onAgentResult(sessionId, fallback);
            return DiagnosisReport.fromSingle(sessionId, fallback);
        }
        DiagnosisContext ctx = new DiagnosisContext(sessionId, problem);
        List<CompletableFuture<DiagnosisResult>> futures = agents.stream()
                .map(agent -> CompletableFuture
                        .supplyAsync(() -> {
                            listener.onAgentStart(sessionId, agent.getName());
                            return agent.diagnose(ctx);
                        }, agentExecutor)
                        .thenApply(result -> {
                            safeListenerResult(listener, sessionId, result);
                            return result;
                        })
                        .exceptionally(ex -> {
                            log.error("Agent [{}] 并行执行异常", agent.getName(), ex);
                            DiagnosisResult failure = DiagnosisResult.failure(agent.getName(), ex.getMessage());
                            safeListenerResult(listener, sessionId, failure);
                            return failure;
                        }))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        List<DiagnosisResult> results = futures.stream()
                .map(CompletableFuture::join)
                .toList();
        return DiagnosisReport.aggregate(sessionId, results);
    }

    private static void safeListenerResult(AgentProgressListener listener, String sessionId,
                                            DiagnosisResult result) {
        try {
            listener.onAgentResult(sessionId, result);
        } catch (Exception ignored) {
            // 回调异常不影响诊断流程
        }
    }

    private DiagnosisResult generalFallback(DiagnosisContext ctx) {
        long start = System.currentTimeMillis();
        String systemPrompt = loadFallbackPrompt();
        String historyText = promptContextBuilder.buildContext(ctx.sessionId());
        String userPrompt = historyText + "用户问题: " + ctx.problem();
        String response = llmClient.chat(systemPrompt, userPrompt);
        long elapsed = System.currentTimeMillis() - start;
        return DiagnosisResult.success(GENERAL_AGENT, response, response, RiskLevel.LOW, elapsed);
    }

    private String loadFallbackPrompt() {
        try {
            return promptService.loadTemplate(PromptKeys.ORCHESTRATOR_ROUTER);
        } catch (Exception e) {
            log.warn("加载 GeneralAgent Prompt 模板失败，使用内置兜底: {}", e.getMessage());
            return FALLBACK_SYSTEM_PROMPT;
        }
    }

    // ---- Session 生命周期 ----

    private Session findOrCreateSession(String sessionId) {
        return sessionRepository.findBySessionId(sessionId)
                .orElseGet(() -> {
                    log.debug("创建新 Session: {}", sessionId);
                    return sessionRepository.save(new Session(sessionId));
                });
    }

    // ---- Record 生命周期 ----

    private DiagnosisRecord createRecord(String sessionId, String problem) {
        DiagnosisRecord record = new DiagnosisRecord(sessionId, problem);
        return recordRepository.save(record);
    }

    private void completeRecord(DiagnosisRecord record, DiagnosisReport report) {
        record.setAgentName(report.agentResults().stream()
                .map(DiagnosisReport.AgentResult::agentName)
                .reduce((a, b) -> a + ", " + b)
                .orElse(GENERAL_AGENT));
        record.setSummary(report.finalSummary());
        record.setStatus(DiagnosisStatus.COMPLETED);
        recordRepository.save(record);
    }

    private void failRecord(DiagnosisRecord record, String error) {
        record.setSummary(error);
        record.setStatus(DiagnosisStatus.FAILED);
        recordRepository.save(record);
    }
}
