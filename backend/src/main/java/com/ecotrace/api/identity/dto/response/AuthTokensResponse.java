package com.ecotrace.api.identity.dto.response;

import java.time.OffsetDateTime;

public record AuthTokensResponse(
        String accessToken,
        OffsetDateTime accessTokenExpiresAt,
        String refreshToken,
        OffsetDateTime refreshTokenExpiresAt,
        UserResponse user) {}
