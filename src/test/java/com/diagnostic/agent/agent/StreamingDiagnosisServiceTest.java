package com.diagnostic.agent.agent;

import com.diagnostic.agent.controller.DiagnosisEvent;
import com.diagnostic.agent.controller.DiagnosisEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StreamingDiagnosisServiceTest {

    @Mock
    private OrchestratorAgent orchestrator;

    @Mock
    private SseEmitter emitter;

    private StreamingDiagnosisService service;

    @BeforeEach
    void setup() {
        service = spy(new StreamingDiagnosisService(orchestrator));
        doAnswer(invocation -> null).when(service).emit(any(SseEmitter.class), any(DiagnosisEvent.class));
    }

    // ---- Scenario 1: 成功事件顺序 START → ROUTING → AGENT_START → AGENT_RESULT → RESULT → COMPLETE ----

    @Test
    void shouldEmitSuccessSequence() {
        DiagnosisResult r = DiagnosisResult.success(
                "SqlDiagnosisAgent", "检测到全表扫描", "建议加索引",
                com.diagnostic.agent.tool.RiskLevel.HIGH, 150L);
        DiagnosisReport mockReport = DiagnosisReport.fromSingle("sess-001", r);
        when(orchestrator.diagnose(eq("sess-001"), eq("SELECT 1"), any(AgentProgressListener.class)))
                .thenAnswer(invocation -> {
                    AgentProgressListener listener = invocation.getArgument(2);
                    listener.onAgentStart("sess-001", "SqlDiagnosisAgent");
                    listener.onAgentResult("sess-001", r);
                    return mockReport;
                });

        service.diagnose("sess-001", "SELECT 1", emitter);

        ArgumentCaptor<DiagnosisEvent> captor = ArgumentCaptor.forClass(DiagnosisEvent.class);
        verify(service, times(6)).emit(eq(emitter), captor.capture());

        List<DiagnosisEventType> types = captor.getAllValues().stream()
                .map(DiagnosisEvent::type).toList();
        assertThat(types).containsExactly(
                DiagnosisEventType.START,
                DiagnosisEventType.ROUTING,
                DiagnosisEventType.AGENT_START,
                DiagnosisEventType.AGENT_RESULT,
                DiagnosisEventType.RESULT,
                DiagnosisEventType.COMPLETE);
    }

    // ---- Scenario 2: 异常事件顺序 START → ROUTING → ERROR → COMPLETE ----

    @Test
    void shouldEmitErrorSequenceOnFailure() {
        when(orchestrator.diagnose(anyString(), anyString(), any(AgentProgressListener.class)))
                .thenThrow(new RuntimeException("诊断失败"));

        service.diagnose("sess-001", "bad query", emitter);

        ArgumentCaptor<DiagnosisEvent> captor = ArgumentCaptor.forClass(DiagnosisEvent.class);
        verify(service, times(4)).emit(eq(emitter), captor.capture());

        List<DiagnosisEventType> types = captor.getAllValues().stream()
                .map(DiagnosisEvent::type).toList();
        assertThat(types).containsExactly(
                DiagnosisEventType.START,
                DiagnosisEventType.ROUTING,
                DiagnosisEventType.ERROR,
                DiagnosisEventType.COMPLETE);
    }

    // ---- Scenario 3: COMPLETE 必然发送 ----

    @Test
    void shouldAlwaysEmitCompleteEvent() {
        when(orchestrator.diagnose(anyString(), anyString(), any(AgentProgressListener.class)))
                .thenThrow(new RuntimeException("任意异常"));

        service.diagnose("sess-001", "test", emitter);

        ArgumentCaptor<DiagnosisEvent> captor = ArgumentCaptor.forClass(DiagnosisEvent.class);
        verify(service, atLeastOnce()).emit(eq(emitter), captor.capture());

        boolean hasComplete = captor.getAllValues().stream()
                .anyMatch(e -> e.type() == DiagnosisEventType.COMPLETE);
        assertThat(hasComplete).isTrue();
    }

    // ---- Scenario 4: AGENT_RESULT 事件携带 DiagnosisResult ----

    @Test
    void shouldAttachDiagnosisResultToAgentResultEvent() {
        DiagnosisResult r = DiagnosisResult.success(
                "SqlDiagnosisAgent", "summary", "detail",
                com.diagnostic.agent.tool.RiskLevel.MEDIUM, 100L);
        DiagnosisReport mockReport = DiagnosisReport.fromSingle("sess-001", r);
        when(orchestrator.diagnose(eq("sess-001"), eq("SELECT 1"), any(AgentProgressListener.class)))
                .thenAnswer(invocation -> {
                    AgentProgressListener listener = invocation.getArgument(2);
                    listener.onAgentStart("sess-001", "SqlDiagnosisAgent");
                    listener.onAgentResult("sess-001", r);
                    return mockReport;
                });

        service.diagnose("sess-001", "SELECT 1", emitter);

        ArgumentCaptor<DiagnosisEvent> captor = ArgumentCaptor.forClass(DiagnosisEvent.class);
        verify(service, times(6)).emit(eq(emitter), captor.capture());

        DiagnosisEvent agentResultEvent = captor.getAllValues().stream()
                .filter(e -> e.type() == DiagnosisEventType.AGENT_RESULT)
                .findFirst().orElseThrow();
        assertThat(agentResultEvent.data()).isInstanceOf(DiagnosisResult.class);
        DiagnosisResult attached = (DiagnosisResult) agentResultEvent.data();
        assertThat(attached.getSummary()).isEqualTo("summary");

        DiagnosisEvent resultEvent = captor.getAllValues().stream()
                .filter(e -> e.type() == DiagnosisEventType.RESULT)
                .findFirst().orElseThrow();
        assertThat(resultEvent.data()).isInstanceOf(DiagnosisReport.class);
        DiagnosisReport attachedReport = (DiagnosisReport) resultEvent.data();
        assertThat(attachedReport.finalSummary()).isEqualTo("summary");
    }
}
