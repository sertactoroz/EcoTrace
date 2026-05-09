package com.ecotrace.api.identity.event;

import com.ecotrace.api.common.event.DomainEvent;
import java.time.OffsetDateTime;
import java.util.UUID;

public record UserRegistered(
        UUID eventId,
        OffsetDateTime occurredAt,
        UUID userId,
        String email) implements DomainEvent {

    public static UserRegistered now(UUID userId, String email) {
        return new UserRegistered(UUID.randomUUID(), OffsetDateTime.now(), userId, email);
    }
}
