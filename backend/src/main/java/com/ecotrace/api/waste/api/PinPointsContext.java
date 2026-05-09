package com.ecotrace.api.waste.api;

import java.math.BigDecimal;

public record PinPointsContext(
        String categoryCode,
        BigDecimal categoryMultiplier,
        String volumeKey) {}
