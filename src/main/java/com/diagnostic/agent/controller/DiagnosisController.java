package com.diagnostic.agent.controller;

import com.diagnostic.agent.agent.DiagnosisReport;
import com.diagnostic.agent.agent.OrchestratorAgent;
import com.diagnostic.agent.common.ApiResponse;
import com.diagnostic.agent.controller.dto.DiagnosisRequest;
import com.diagnostic.agent.controller.dto.DiagnosisResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DiagnosisController {

    private final OrchestratorAgent orchestrator;

    public DiagnosisController(OrchestratorAgent orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping("/api/diagnose")
    public ApiResponse<DiagnosisResponse> diagnose(@Valid @RequestBody DiagnosisRequest request) {
        DiagnosisReport report = orchestrator.diagnose(request.sessionId(), request.problem());
        DiagnosisResponse response = DiagnosisResponse.from(report, request.sessionId());
        return ApiResponse.success(response);
    }
}
