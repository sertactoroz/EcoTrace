package com.ecotrace.api.collection.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CollectionReversed(
        UUID collectionId,
        UUID wastePointId,
        UUID collectorUserId,
        UUID reversedByUserId,
        String reason,
        OffsetDateTime reversedAt) {}
