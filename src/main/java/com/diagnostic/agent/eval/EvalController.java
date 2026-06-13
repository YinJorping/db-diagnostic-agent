package com.diagnostic.agent.eval;

import com.diagnostic.agent.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalRunner evalRunner;

    public EvalController(EvalRunner evalRunner) {
        this.evalRunner = evalRunner;
    }

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody Map<String, Object> body) {
        String domain = (String) body.getOrDefault("domain", "*");
        String mode = (String) body.getOrDefault("mode", "AUTO");
        @SuppressWarnings("unchecked")
        Map<String, String> promptOverrides = (Map<String, String>) body.getOrDefault("promptOverrides", Map.of());

        EvalRun run = evalRunner.start(domain, mode, promptOverrides);
        return ApiResponse.success(Map.of(
                "runId", run.getRunId(),
                "status", run.getStatus().name(),
                "triggeredAt", run.getStartTime().toString()
        ));
    }

    @GetMapping("/report/{runId}")
    public ApiResponse<?> report(@PathVariable String runId) {
        EvalRun run = evalRunner.getRun(runId);
        if (run == null) {
            return ApiResponse.notFound("Eval run not found: " + runId);
        }
        if (run.getStatus() == EvalRunStatus.RUNNING || run.getStatus() == EvalRunStatus.PENDING) {
            return ApiResponse.success(Map.of(
                    "runId", run.getRunId(),
                    "status", run.getStatus().name(),
                    "message", "Evaluation is still running"
            ));
        }
        return ApiResponse.success(run.getReport());
    }

}
