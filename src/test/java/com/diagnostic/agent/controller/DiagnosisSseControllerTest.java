package com.diagnostic.agent.controller;

import com.diagnostic.agent.agent.StreamingDiagnosisService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DiagnosisSseController.class)
class DiagnosisSseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StreamingDiagnosisService streamingService;

    @Test
    void shouldStreamDiagnosisEvents() throws Exception {
        SseEmitter emitter = new SseEmitter(5000L);
        when(streamingService.createEmitter()).thenReturn(emitter);

        doAnswer(invocation -> {
            SseEmitter em = invocation.getArgument(2);
            em.send(SseEmitter.event().name("START").data(
                    DiagnosisEvent.of(DiagnosisEventType.START, "Starting")));
            em.send(SseEmitter.event().name("RESULT").data(
                    DiagnosisEvent.of(DiagnosisEventType.RESULT, "Done")));
            em.complete();
            return CompletableFuture.completedFuture(null);
        }).when(streamingService).diagnose(anyString(), anyString(), any());

        MvcResult mvcResult = mockMvc.perform(get("/api/diagnose/stream")
                        .param("sessionId", "sess-001")
                        .param("problem", "SELECT 1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.TEXT_EVENT_STREAM))
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body).contains("event:START");
                    assertThat(body).contains("event:RESULT");
                });
    }

    @Test
    void shouldSendErrorEventOnFailure() throws Exception {
        SseEmitter emitter = new SseEmitter(5000L);
        when(streamingService.createEmitter()).thenReturn(emitter);

        doAnswer(invocation -> {
            SseEmitter em = invocation.getArgument(2);
            em.send(SseEmitter.event().name("START").data(
                    DiagnosisEvent.of(DiagnosisEventType.START, "Starting")));
            em.send(SseEmitter.event().name("ERROR").data(
                    DiagnosisEvent.of(DiagnosisEventType.ERROR, "Failed")));
            em.complete();
            return CompletableFuture.completedFuture(null);
        }).when(streamingService).diagnose(anyString(), anyString(), any());

        MvcResult mvcResult = mockMvc.perform(get("/api/diagnose/stream")
                        .param("sessionId", "sess-001")
                        .param("problem", "SELECT 1"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    assertThat(body).contains("event:ERROR");
                });
    }
}
