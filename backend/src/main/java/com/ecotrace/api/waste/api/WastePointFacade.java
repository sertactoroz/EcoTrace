package com.ecotrace.api.waste.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface WastePointFacade {

    ClaimedPin claimForUser(UUID pinId, UUID userId, OffsetDateTime expiresAt);

    void releaseClaim(UUID pinId, UUID userId);

    PinPointsContext getPointsContext(UUID pinId);

    void markVerified(UUID pinId, UUID collectionId);

    void markRejected(UUID pinId);
}
