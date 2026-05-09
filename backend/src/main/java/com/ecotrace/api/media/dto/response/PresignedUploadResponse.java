package com.ecotrace.api.media.dto.response;

import java.time.OffsetDateTime;

public record PresignedUploadResponse(
        String storageKey,
        String uploadUrl,
        String publicUrl,
        String method,
        String contentType,
        OffsetDateTime expiresAt) {}
