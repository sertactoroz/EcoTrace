package com.ecotrace.api.media.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record PresignUploadRequest(
        @NotBlank
        @Pattern(regexp = "image/(jpeg|png|webp|heic)")
        String contentType,
        @Positive
        long sizeBytes,
        @Pattern(regexp = "[a-z][a-z0-9_-]{0,31}")
        String purpose) {}
