package com.diagnostic.agent.common;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TRACE_ID_KEY = "traceId";

    // ---- 第一层：@Valid 校验失败（@RequestBody） ----

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<?> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return ApiResponse.badRequest(msg).withTraceId(MDC.get(TRACE_ID_KEY));
    }

    // ---- 第二层：业务异常 ----

    @ExceptionHandler(BusinessException.class)
    public ApiResponse<?> handleBusiness(BusinessException ex) {
        return ApiResponse.error(ex.getCode(), ex.getMessage())
                .withTraceId(MDC.get(TRACE_ID_KEY));
    }

    // ---- 第三层：@RequestParam / @PathVariable 校验 ----

    @ExceptionHandler(ConstraintViolationException.class)
    public ApiResponse<?> handleConstraintViolation(ConstraintViolationException ex) {
        String msg = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        return ApiResponse.badRequest(msg).withTraceId(MDC.get(TRACE_ID_KEY));
    }

    // ---- 第四层：IllegalArgumentException 显式拦截 ----

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<?> handleIllegalArgument(IllegalArgumentException ex) {
        return ApiResponse.badRequest(ex.getMessage()).withTraceId(MDC.get(TRACE_ID_KEY));
    }

    // ---- 第五层：兜底 ----

    @ExceptionHandler(Exception.class)
    public ApiResponse<?> handleGeneral(Exception ex) {
        log.error("Unexpected error [traceId={}]", MDC.get(TRACE_ID_KEY), ex);
        return ApiResponse.error(ApiResponse.BizCode.UNKNOWN_ERROR, "系统内部错误")
                .withTraceId(MDC.get(TRACE_ID_KEY));
    }
}
