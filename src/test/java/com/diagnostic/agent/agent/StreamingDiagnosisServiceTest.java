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
        // 拦截 emit，避免真实 HTTP 写入
        doAnswer(invocation -> null).when(service).emit(any(SseEmitter.class), any(DiagnosisEvent.class));
    }

    // ---- Scenario 1: 成功事件顺序 START → ROUTING → RESULT → COMPLETE ----

    @Test
    void shouldEmitSuccessSequence() {
        DiagnosisResult mockResult = DiagnosisResult.success(
                "SqlDiagnosisAgent", "检测到全表扫描", "建议加索引",
                com.diagnostic.agent.tool.RiskLevel.HIGH, 150L);
        when(orchestrator.diagnose("sess-001", "SELECT 1")).thenReturn(mockResult);

        service.diagnose("sess-001", "SELECT 1", emitter);

        ArgumentCaptor<DiagnosisEvent> captor = ArgumentCaptor.forClass(DiagnosisEvent.class);
        verify(service, times(4)).emit(eq(emitter), captor.capture());

        List<DiagnosisEventType> types = captor.getAllValues().stream()
                .map(DiagnosisEvent::type).toList();
        assertThat(types).containsExactly(
                DiagnosisEventType.START,
                DiagnosisEventType.ROUTING,
                DiagnosisEventType.RESULT,
                DiagnosisEventType.COMPLETE);
    }

    // ---- Scenario 2: 异常事件顺序 START → ROUTING → ERROR → COMPLETE ----

    @Test
    void shouldEmitErrorSequenceOnFailure() {
        when(orchestrator.diagnose("sess-001", "bad query"))
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
        when(orchestrator.diagnose(anyString(), anyString()))
                .thenThrow(new RuntimeException("任意异常"));

        service.diagnose("sess-001", "test", emitter);

        ArgumentCaptor<DiagnosisEvent> captor = ArgumentCaptor.forClass(DiagnosisEvent.class);
        verify(service, atLeastOnce()).emit(eq(emitter), captor.capture());

        boolean hasComplete = captor.getAllValues().stream()
                .anyMatch(e -> e.type() == DiagnosisEventType.COMPLETE);
        assertThat(hasComplete).isTrue();
    }

    // ---- Scenario 4: RESULT 事件携带 DiagnosisResult ----

    @Test
    void shouldAttachDiagnosisResultToResultEvent() {
        DiagnosisResult mockResult = DiagnosisResult.success(
                "SqlDiagnosisAgent", "summary", "detail",
                com.diagnostic.agent.tool.RiskLevel.MEDIUM, 100L);
        when(orchestrator.diagnose("sess-001", "SELECT 1")).thenReturn(mockResult);

        service.diagnose("sess-001", "SELECT 1", emitter);

        ArgumentCaptor<DiagnosisEvent> captor = ArgumentCaptor.forClass(DiagnosisEvent.class);
        verify(service, times(4)).emit(eq(emitter), captor.capture());

        DiagnosisEvent resultEvent = captor.getAllValues().stream()
                .filter(e -> e.type() == DiagnosisEventType.RESULT)
                .findFirst().orElseThrow();
        assertThat(resultEvent.data()).isInstanceOf(DiagnosisResult.class);
        DiagnosisResult attached = (DiagnosisResult) resultEvent.data();
        assertThat(attached.getAgentName()).isEqualTo("SqlDiagnosisAgent");
    }
}
