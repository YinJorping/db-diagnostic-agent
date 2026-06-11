package com.diagnostic.agent.controller;

import com.diagnostic.agent.common.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/test")
public class TestController {

    // ---- 验证：IllegalArgumentException → 4000 ----

    @GetMapping("/error")
    public String error() {
        throw new IllegalArgumentException("参数错误");
    }

    // ---- 验证：BusinessException → 业务码 ----

    @GetMapping("/business")
    public String business() {
        throw BusinessException.notFound("会话不存在");
    }

    // ---- 验证：兜底 5000 ----

    @GetMapping("/unexpected")
    public String unexpected() {
        throw new RuntimeException("数据库连接失败");
    }

    // ---- 验证：正常返回 + traceId ----

    @GetMapping("/ok")
    public String ok() {
        return "ok";
    }

    // ---- 验证：@Valid 校验 ----

    @PostMapping("/validate")
    public String validate(@Valid @RequestBody ProblemRequest request) {
        return request.problem;
    }

    public record ProblemRequest(
            @NotBlank(message = "problem不能为空") String problem) {}
}
