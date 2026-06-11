package com.diagnostic.agent.controller;

import com.diagnostic.agent.agent.StreamingDiagnosisService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class DiagnosisSseController {

    private final StreamingDiagnosisService streamingService;

    public DiagnosisSseController(StreamingDiagnosisService streamingService) {
        this.streamingService = streamingService;
    }

    @GetMapping(value = "/api/diagnose/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String sessionId, @RequestParam String problem) {
        SseEmitter emitter = streamingService.createEmitter();
        streamingService.diagnose(sessionId, problem, emitter);
        return emitter;
    }
}
