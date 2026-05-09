package com.ecotrace.api.waste.event;

import com.ecotrace.api.common.event.DomainEvent;
import java.time.OffsetDateTime;
import java.util.UUID;

public record WastePointCreated(
        UUID eventId,
        OffsetDateTime occurredAt,
        UUID wastePointId,
        UUID reportedByUserId,
        String categoryCode) implements DomainEvent {

    public static WastePointCreated now(UUID wastePointId, UUID reportedByUserId, String categoryCode) {
        return new WastePointCreated(UUID.randomUUID(), OffsetDateTime.now(), wastePointId, reportedByUserId, categoryCode);
    }
}
