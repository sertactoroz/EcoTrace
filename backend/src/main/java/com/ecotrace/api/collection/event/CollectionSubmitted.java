package com.ecotrace.api.collection.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CollectionSubmitted(
        UUID collectionId,
        UUID wastePointId,
        UUID collectorUserId,
        int evidenceCount,
        OffsetDateTime submittedAt) {

    public static CollectionSubmitted now(UUID collectionId, UUID wastePointId,
                                          UUID collectorUserId, int evidenceCount) {
        return new CollectionSubmitted(collectionId, wastePointId, collectorUserId,
                evidenceCount, OffsetDateTime.now());
    }
}
