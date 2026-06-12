package com.diagnostic.agent.agent;

import com.diagnostic.agent.controller.DiagnosisEvent;
import com.diagnostic.agent.controller.DiagnosisEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

@Service
public class StreamingDiagnosisService {

    private static final Logger log = LoggerFactory.getLogger(StreamingDiagnosisService.class);
    private static final long SSE_TIMEOUT_MS = 300_000L;

    private final OrchestratorAgent orchestrator;

    public StreamingDiagnosisService(OrchestratorAgent orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Async
    public CompletableFuture<Void> diagnose(String sessionId, String problem, SseEmitter emitter) {
        try {
            emit(emitter, DiagnosisEvent.of(DiagnosisEventType.START, "开始诊断"));
            emit(emitter, DiagnosisEvent.of(DiagnosisEventType.ROUTING, "问题路由中"));

            AgentProgressListener listener = new AgentProgressListener() {
                @Override
                public void onAgentStart(String sid, String agentName) {
                    safeEmit(emitter, DiagnosisEvent.of(DiagnosisEventType.AGENT_START,
                            agentName + " 开始诊断"));
                }

                @Override
                public void onAgentResult(String sid, DiagnosisResult result) {
                    safeEmit(emitter, DiagnosisEvent.of(DiagnosisEventType.AGENT_RESULT,
                            result.getAgentName() + " 完成", result));
                }
            };

            DiagnosisReport report = orchestrator.diagnose(sessionId, problem, listener);
            emit(emitter, DiagnosisEvent.of(DiagnosisEventType.RESULT, "诊断完成", report));
        } catch (Exception e) {
            log.error("SSE 诊断失败: sessionId={}", sessionId, e);
            safeEmit(emitter, DiagnosisEvent.of(DiagnosisEventType.ERROR, e.getMessage()));
        } finally {
            safeEmit(emitter, DiagnosisEvent.of(DiagnosisEventType.COMPLETE, "诊断流程结束"));
            safeComplete(emitter);
        }
        return CompletableFuture.completedFuture(null);
    }

    public SseEmitter createEmitter() {
        return new SseEmitter(SSE_TIMEOUT_MS);
    }

    public void emit(SseEmitter emitter, DiagnosisEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type().name())
                    .data(event));
        } catch (IOException e) {
            throw new RuntimeException("SSE emit failed", e);
        }
    }

    private void safeEmit(SseEmitter emitter, DiagnosisEvent event) {
        try {
            emit(emitter, event);
        } catch (RuntimeException ignored) {
            // emitter 可能已断开
        }
    }

    private void safeComplete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // 已经关闭
        }
    }
}
