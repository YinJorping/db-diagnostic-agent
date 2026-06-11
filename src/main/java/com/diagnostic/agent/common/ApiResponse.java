package com.diagnostic.agent.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    /** 业务状态码：0=成功，非0=各类业务异常 */
    private int code;

    /** 响应描述 */
    private String message;

    /** 响应数据负载 */
    private T data;

    /** 链路追踪 ID（由 Controller 层注入） */
    private String traceId;

    /** 响应生成时间 */
    private Instant timestamp;

    // ---- 私有构造 ----

    private ApiResponse(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
        this.timestamp = Instant.now();
    }

    // ---- 成功工厂 ----

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(BizCode.SUCCESS, "success", data, null);
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(BizCode.SUCCESS, message, data, null);
    }

    // ---- 通用错误工厂 ----

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null, null);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(BizCode.UNKNOWN_ERROR, message, null, null);
    }

    // ---- 快捷错误工厂 ----

    public static <T> ApiResponse<T> badRequest(String message) {
        return new ApiResponse<>(BizCode.BAD_REQUEST, message, null, null);
    }

    public static <T> ApiResponse<T> notFound(String message) {
        return new ApiResponse<>(BizCode.NOT_FOUND, message, null, null);
    }

    // ---- traceId 注入 ----

    public ApiResponse<T> withTraceId(String traceId) {
        this.traceId = traceId;
        return this;
    }

    // ---- 业务状态码常量 ----

    public static final class BizCode {
        public static final int SUCCESS        = 0;
        public static final int BAD_REQUEST    = 4000;
        public static final int NOT_FOUND      = 4004;
        public static final int UNKNOWN_ERROR  = 5000;

        private BizCode() {}
    }
}
