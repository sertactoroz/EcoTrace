package com.ecotrace.api.identity.api;

import java.util.UUID;

public record UserPointsSnapshot(UUID userId, long totalPoints) {}
