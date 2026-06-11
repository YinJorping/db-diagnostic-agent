package com.diagnostic.agent.common;

import lombok.Getter;

@Getter
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BusinessException(String message) {
        this(ApiResponse.BizCode.UNKNOWN_ERROR, message);
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(ApiResponse.BizCode.BAD_REQUEST, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(ApiResponse.BizCode.NOT_FOUND, message);
    }
}
