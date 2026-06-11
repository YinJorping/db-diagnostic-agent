package com.diagnostic.agent.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    // ==================== success ====================

    @Test
    void successShouldReturnCodeZero() {
        ApiResponse<String> resp = ApiResponse.success("hello");

        assertThat(resp.getCode()).isEqualTo(ApiResponse.BizCode.SUCCESS);
        assertThat(resp.getData()).isEqualTo("hello");
        assertThat(resp.getMessage()).isEqualTo("success");
        assertThat(resp.getTimestamp()).isNotNull();
        assertThat(resp.getTraceId()).isNull();
    }

    @Test
    void successWithCustomMessage() {
        ApiResponse<String> resp = ApiResponse.success("data", "custom message");

        assertThat(resp.getCode()).isEqualTo(ApiResponse.BizCode.SUCCESS);
        assertThat(resp.getMessage()).isEqualTo("custom message");
        assertThat(resp.getData()).isEqualTo("data");
    }

    @Test
    void successWithNullData() {
        ApiResponse<Void> resp = ApiResponse.success(null);

        assertThat(resp.getCode()).isEqualTo(ApiResponse.BizCode.SUCCESS);
        assertThat(resp.getData()).isNull();
    }

    // ==================== error ====================

    @Test
    void errorWithCodeAndMessage() {
        ApiResponse<Void> resp = ApiResponse.error(1001, "参数错误");

        assertThat(resp.getCode()).isEqualTo(1001);
        assertThat(resp.getMessage()).isEqualTo("参数错误");
        assertThat(resp.getData()).isNull();
    }

    @Test
    void errorDefaultsToUnknownError() {
        ApiResponse<Void> resp = ApiResponse.error("内部错误");

        assertThat(resp.getCode()).isEqualTo(ApiResponse.BizCode.UNKNOWN_ERROR);
        assertThat(resp.getMessage()).isEqualTo("内部错误");
    }

    // ==================== 快捷方法 ====================

    @Test
    void badRequestShortcut() {
        ApiResponse<Void> resp = ApiResponse.badRequest("缺少必填参数");

        assertThat(resp.getCode()).isEqualTo(ApiResponse.BizCode.BAD_REQUEST);
        assertThat(resp.getMessage()).isEqualTo("缺少必填参数");
    }

    @Test
    void notFoundShortcut() {
        ApiResponse<Void> resp = ApiResponse.notFound("会话不存在");

        assertThat(resp.getCode()).isEqualTo(ApiResponse.BizCode.NOT_FOUND);
        assertThat(resp.getMessage()).isEqualTo("会话不存在");
    }

    // ==================== traceId ====================

    @Test
    void withTraceIdShouldOverride() {
        ApiResponse<String> resp = ApiResponse.success("ok").withTraceId("trace-abc-123");

        assertThat(resp.getTraceId()).isEqualTo("trace-abc-123");
    }

    @Test
    void traceIdIsNullByDefault() {
        ApiResponse<String> resp = ApiResponse.success("ok");

        assertThat(resp.getTraceId()).isNull();
    }

    // ==================== JSON 序列化 ====================

    @Test
    void serializationShouldIncludeAllFields() throws Exception {
        ApiResponse<String> resp = ApiResponse.success("hello").withTraceId("t1");

        String json = mapper.writeValueAsString(resp);

        assertThat(json).contains("\"code\":0");
        assertThat(json).contains("\"message\":\"success\"");
        assertThat(json).contains("\"data\":\"hello\"");
        assertThat(json).contains("\"traceId\":\"t1\"");
        assertThat(json).contains("\"timestamp\":");
    }

    @Test
    void serializationShouldExcludeNullData() throws Exception {
        ApiResponse<Void> resp = ApiResponse.error("error");

        String json = mapper.writeValueAsString(resp);

        // data 为 null 时应被 @JsonInclude(NON_NULL) 排除
        assertThat(json).doesNotContain("\"data\"");
    }

    @Test
    void serializationShouldExcludeNullTraceId() throws Exception {
        ApiResponse<String> resp = ApiResponse.success("ok");

        String json = mapper.writeValueAsString(resp);

        assertThat(json).doesNotContain("\"traceId\"");
    }

    // ==================== timestamp ====================

    @Test
    void timestampShouldUseInstantType() {
        ApiResponse<String> resp = ApiResponse.success("ok");

        assertThat(resp.getTimestamp()).isInstanceOf(java.time.Instant.class);
    }

    @Test
    void timestampShouldBeCloseToNow() {
        Instant before = Instant.now();
        ApiResponse<String> resp = ApiResponse.success("ok");
        Instant after = Instant.now();

        assertThat(resp.getTimestamp()).isBetween(before, after);
    }
}
