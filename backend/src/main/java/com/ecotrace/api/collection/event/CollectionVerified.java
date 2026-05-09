package com.ecotrace.api.collection.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CollectionVerified(
        UUID collectionId,
        UUID wastePointId,
        UUID collectorUserId,
        UUID verifiedByUserId,
        int pointsAwarded,
        OffsetDateTime verifiedAt) {}
