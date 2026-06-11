package com.diagnostic.agent.controller.dto;

import jakarta.validation.constraints.NotBlank;

public record DiagnosisRequest(
        @NotBlank(message = "sessionId不能为空") String sessionId,
        @NotBlank(message = "problem不能为空") String problem) {}
