package com.ecotrace.api.common.event;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface DomainEvent {
    UUID eventId();
    OffsetDateTime occurredAt();
}
