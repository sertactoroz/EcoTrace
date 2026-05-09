package com.ecotrace.api.identity.api;

import java.util.UUID;

public record UserSummary(UUID userId, String displayName, String avatarUrl, int level) {}
