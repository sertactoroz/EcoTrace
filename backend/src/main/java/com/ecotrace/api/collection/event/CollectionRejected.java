package com.ecotrace.api.collection.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CollectionRejected(
        UUID collectionId,
        UUID wastePointId,
        UUID collectorUserId,
        UUID rejectedByUserId,
        String reason,
        OffsetDateTime rejectedAt) {}
