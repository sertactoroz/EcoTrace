package com.ecotrace.api.collection.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CollectionClaimed(
        UUID collectionId,
        UUID wastePointId,
        UUID collectorUserId,
        OffsetDateTime claimedAt,
        OffsetDateTime claimExpiresAt) {

    public static CollectionClaimed now(UUID collectionId, UUID wastePointId, UUID collectorUserId,
                                        OffsetDateTime claimExpiresAt) {
        return new CollectionClaimed(collectionId, wastePointId, collectorUserId,
                OffsetDateTime.now(), claimExpiresAt);
    }
}
