package com.diagnostic.agent.agent;

import com.diagnostic.agent.common.security.SensitiveDataMasker;
import com.diagnostic.agent.memory.ChatMemoryStore;
import com.diagnostic.agent.memory.MessageType;
import com.diagnostic.agent.memory.StoredMessage;
import com.diagnostic.agent.repository.DiagnosisRecord;
import com.diagnostic.agent.repository.DiagnosisRecordRepository;
import com.diagnostic.agent.repository.Session;
import com.diagnostic.agent.repository.SessionRepository;
import com.diagnostic.agent.tool.RiskLevel;
import com.diagnostic.agent.tool.Tool;
import com.diagnostic.agent.tool.ToolRegistry;
import com.diagnostic.agent.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final ToolRegistry toolRegistry;
    private final Executor agentExecutor;
    private final SensitiveDataMasker sensitiveDataMasker;

    public OrchestratorAgent(SessionRepository sessionRepository,
                             DiagnosisRecordRepository recordRepository,
                             AgentRouter agentRouter,
                             PromptService promptService,
                             LlmClient llmClient,
                             ChatMemoryStore memoryStore,
                             PromptContextBuilder promptContextBuilder,
                             ToolRegistry toolRegistry,
                             @Qualifier("agentExecutor") Executor agentExecutor,
                             SensitiveDataMasker sensitiveDataMasker) {
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.agentRouter = agentRouter;
        this.promptService = promptService;
        this.llmClient = llmClient;
        this.memoryStore = memoryStore;
        this.promptContextBuilder = promptContextBuilder;
        this.toolRegistry = toolRegistry;
        this.agentExecutor = agentExecutor;
        this.sensitiveDataMasker = sensitiveDataMasker;
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

        // V1 Scope C5 + Section 2 跨域能力: 锁阻塞检测 + 连接状态快照
        List<DiagnosisResult> sharedResults = executeSharedTools(sessionId, problem, listener);

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
        List<DiagnosisResult> results = new ArrayList<>(sharedResults);
        futures.stream().map(CompletableFuture::join).forEach(results::add);

        SummarizationResult summarization = results.size() > 1
                ? summarizeResults(ctx.problem(), results)
                : new SummarizationResult(results.get(0).getSummary(), 0, 0);
        return DiagnosisReport.aggregate(sessionId, results, summarization.summary(),
                summarization.promptTokens(), summarization.completionTokens());
    }

    /**
     * 执行共享诊断工具（锁阻塞检测 + 连接状态快照）。
     * V1 Scope Section 2 跨域能力 — 每次诊断自动前置采集。
     */
    private List<DiagnosisResult> executeSharedTools(String sessionId, String problem,
                                                     AgentProgressListener listener) {
        List<DiagnosisResult> results = new ArrayList<>();
        toolRegistry.get("LockTool").ifPresent(tool -> {
            try {
                ToolResult tr = tool.execute(Map.of());
                DiagnosisResult dr = toDiagnosisResult(tr);
                listener.onAgentStart(sessionId, tool.getName());
                listener.onAgentResult(sessionId, dr);
                results.add(dr);
            } catch (Exception e) {
                log.warn("共享 Tool [{}] 执行失败: {}", tool.getName(), e.getMessage());
            }
        });
        return results;
    }

    private static DiagnosisResult toDiagnosisResult(ToolResult tr) {
        if (tr.isSuccess()) {
            return DiagnosisResult.success(tr.getToolName(), tr.getSummary(),
                    tr.getDetail() != null ? tr.getDetail().toString() : "",
                    tr.getRisk(), tr.getExecutionTimeMs(), 0, 0);
        }
        return DiagnosisResult.failure(tr.getToolName(), tr.getError());
    }

    /**
     * 调用 LLM 对多个 Agent 的诊断结果进行语义聚合，生成统一摘要。
     */
    private SummarizationResult summarizeResults(String problem, List<DiagnosisResult> results) {
        try {
            String systemPrompt = promptService.loadTemplate(PromptKeys.SUMMARIZER_AGGREGATION);
            String agentReports = buildAgentReportsText(results);
            String userPrompt = "用户问题: " + problem + "\n\n" + agentReports;
            userPrompt = sensitiveDataMasker.mask(userPrompt);
            String summary = llmClient.chat(systemPrompt, userPrompt);
            LlmClient.LlmUsage usage = llmClient.lastUsage();
            return new SummarizationResult(summary,
                    usage != null ? usage.promptTokens() : 0,
                    usage != null ? usage.completionTokens() : 0);
        } catch (Exception e) {
            log.warn("LLM 聚合摘要失败，降级为简单拼接: {}", e.getMessage());
            return new SummarizationResult(DiagnosisReport.aggregate("_", results).finalSummary(), 0, 0);
        }
    }

    private record SummarizationResult(String summary, int promptTokens, int completionTokens) {}

    private String buildAgentReportsText(List<DiagnosisResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是各诊断专家的分析结果：\n\n");
        for (int i = 0; i < results.size(); i++) {
            DiagnosisResult r = results.get(i);
            sb.append("【").append(r.getAgentName()).append("】");
            if (r.isSuccess()) {
                sb.append("风险等级: ").append(r.getRisk()).append("\n");
                sb.append(r.getSummary()).append("\n");
            } else {
                sb.append("诊断失败: ").append(r.getError()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
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
        userPrompt = sensitiveDataMasker.mask(userPrompt);
        String response = llmClient.chat(systemPrompt, userPrompt);
        long elapsed = System.currentTimeMillis() - start;
        LlmClient.LlmUsage usage = llmClient.lastUsage();
        return DiagnosisResult.success(GENERAL_AGENT, response, response, RiskLevel.LOW, elapsed,
                usage != null ? usage.promptTokens() : 0,
                usage != null ? usage.completionTokens() : 0);
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
