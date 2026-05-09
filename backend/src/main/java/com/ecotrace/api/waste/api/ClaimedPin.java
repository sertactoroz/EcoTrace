package com.ecotrace.api.waste.api;

import java.util.UUID;

public record ClaimedPin(UUID pinId, UUID reportedByUserId) {}
