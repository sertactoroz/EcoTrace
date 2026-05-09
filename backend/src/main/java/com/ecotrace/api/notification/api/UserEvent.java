package com.ecotrace.api.notification.api;

import java.time.OffsetDateTime;
import java.util.Map;

public record UserEvent(String type, Map<String, Object> data, OffsetDateTime occurredAt) {}
