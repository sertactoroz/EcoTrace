package com.ecotrace.api.collection.dto.response;

import com.ecotrace.api.collection.entity.CollectionStatus;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record CollectionResponse(
        UUID id,
        UUID wastePointId,
        UUID collectorUserId,
        CollectionStatus status,
        OffsetDateTime claimedAt,
        OffsetDateTime claimExpiresAt,
        OffsetDateTime submittedAt,
        String notes,
        List<EvidenceItem> evidence,
        int pointsAwarded) {

    public record EvidenceItem(UUID id, String storageKey, String url, String kind) {}
}
