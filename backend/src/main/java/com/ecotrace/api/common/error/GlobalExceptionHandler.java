package com.ecotrace.api.common.error;

import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest req) {
        ErrorCode code = ex.errorCode();
        return build(code, ex.getMessage(), ex.fieldErrors(), req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<BusinessException.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new BusinessException.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_ERROR, "Validation failed", fields, req);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuth(AuthenticationException ex, HttpServletRequest req) {
        return build(ErrorCode.UNAUTHENTICATED, ex.getMessage(), List.of(), req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleForbidden(AccessDeniedException ex, HttpServletRequest req) {
        return build(ErrorCode.FORBIDDEN, ex.getMessage(), List.of(), req);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoHandlerFoundException ex, HttpServletRequest req) {
        return build(ErrorCode.NOT_FOUND, "Resource not found", List.of(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAnything(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL, "Internal server error", List.of(), req);
    }

    private ResponseEntity<ApiError> build(
            ErrorCode code, String message, List<BusinessException.FieldError> fields, HttpServletRequest req) {
        ApiError body = new ApiError(
                new ApiError.Error(code.code(), message, fields.isEmpty() ? null : fields),
                OffsetDateTime.now(),
                req.getHeader("X-Request-Id"));
        return ResponseEntity.status(code.status()).body(body);
    }
}
