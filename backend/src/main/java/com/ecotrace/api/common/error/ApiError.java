package com.ecotrace.api.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        Error error,
        OffsetDateTime timestamp,
        String requestId) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(
            String code,
            String message,
            List<BusinessException.FieldError> fieldErrors) {}
}
