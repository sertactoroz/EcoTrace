package com.ecotrace.api.identity.dto.response;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String displayName,
        String avatarUrl,
        long totalPoints,
        int level,
        String status) {}
