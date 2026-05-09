package com.ecotrace.api.gamification.api;

import java.util.UUID;

public record PointsAward(
        UUID transactionId,
        int delta,
        long newTotalPoints,
        int newLevel,
        boolean idempotent) {}
