package com.diagnostic.agent.agent;

import com.diagnostic.agent.repository.DiagnosisRecord;
import com.diagnostic.agent.repository.DiagnosisRecordRepository;
import com.diagnostic.agent.repository.Session;
import com.diagnostic.agent.repository.SessionRepository;
import com.diagnostic.agent.tool.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

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

    public OrchestratorAgent(SessionRepository sessionRepository,
                             DiagnosisRecordRepository recordRepository,
                             AgentRouter agentRouter,
                             PromptService promptService,
                             LlmClient llmClient) {
        this.sessionRepository = sessionRepository;
        this.recordRepository = recordRepository;
        this.agentRouter = agentRouter;
        this.promptService = promptService;
        this.llmClient = llmClient;
    }

    public DiagnosisResult diagnose(String sessionId, String problem) {
        findOrCreateSession(sessionId);
        DiagnosisRecord record = createRecord(sessionId, problem);

        try {
            DiagnosisResult result = executeDiagnosis(problem);
            completeRecord(record, result);
            return result;
        } catch (Exception e) {
            log.error("诊断失败: sessionId={}, problem={}", sessionId, problem, e);
            failRecord(record, e.getMessage());
            throw e;
        }
    }

    // ---- 诊断执行 ----

    private DiagnosisResult executeDiagnosis(String problem) {
        Agent agent = agentRouter.route(problem);
        if (agent != null) {
            return agent.diagnose(problem);
        }
        return generalFallback(problem);
    }

    private DiagnosisResult generalFallback(String problem) {
        long start = System.currentTimeMillis();
        String systemPrompt = loadFallbackPrompt();
        String userPrompt = "用户问题: " + problem;
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

    private void completeRecord(DiagnosisRecord record, DiagnosisResult result) {
        record.setAgentName(result.getAgentName());
        record.setSummary(result.getSummary());
        record.setStatus(DiagnosisStatus.COMPLETED);
        recordRepository.save(record);
    }

    private void failRecord(DiagnosisRecord record, String error) {
        record.setSummary(error);
        record.setStatus(DiagnosisStatus.FAILED);
        recordRepository.save(record);
    }
}
