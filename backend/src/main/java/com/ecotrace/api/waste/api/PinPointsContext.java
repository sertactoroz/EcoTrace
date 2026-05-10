package com.ecotrace.api.waste.api;

import java.math.BigDecimal;
import java.util.UUID;

public record PinPointsContext(
        String categoryCode,
        BigDecimal categoryMultiplier,
        String volumeKey,
        UUID reportedByUserId) {}
