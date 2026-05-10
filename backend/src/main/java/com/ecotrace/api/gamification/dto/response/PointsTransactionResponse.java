package com.ecotrace.api.gamification.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PointsTransactionResponse(
        UUID id,
        int delta,
        String reason,
        UUID collectionId,
        UUID wastePointId,
        OffsetDateTime createdAt) {}
