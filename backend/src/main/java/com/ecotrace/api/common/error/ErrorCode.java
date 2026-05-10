package com.ecotrace.api.common.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"),
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "INVALID_REQUEST"),
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "INVALID_TOKEN"),
    TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "TOKEN_EXPIRED"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN"),
    USER_SUSPENDED(HttpStatus.FORBIDDEN, "USER_SUSPENDED"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND"),
    CONFLICT(HttpStatus.CONFLICT, "CONFLICT"),
    ALREADY_CLAIMED(HttpStatus.CONFLICT, "ALREADY_CLAIMED"),
    INVALID_STATE(HttpStatus.CONFLICT, "INVALID_STATE"),
    FRAUD_GATE_FAILED(HttpStatus.UNPROCESSABLE_ENTITY, "FRAUD_GATE_FAILED"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED"),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL");

    private final HttpStatus status;
    private final String code;

    ErrorCode(HttpStatus status, String code) {
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
