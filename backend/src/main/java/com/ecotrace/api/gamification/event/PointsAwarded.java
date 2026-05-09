package com.ecotrace.api.gamification.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record PointsAwarded(
        UUID transactionId,
        UUID userId,
        UUID collectionId,
        UUID wastePointId,
        int delta,
        long newTotalPoints,
        int newLevel,
        String reason,
        OffsetDateTime occurredAt) {}
