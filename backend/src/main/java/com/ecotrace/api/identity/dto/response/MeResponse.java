package com.ecotrace.api.identity.dto.response;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

public record MeResponse(
        UUID id,
        String email,
        String displayName,
        String avatarUrl,
        String bio,
        long totalPoints,
        int level,
        String status,
        String locale,
        OffsetDateTime lastActiveAt,
        Set<String> roles) {}
