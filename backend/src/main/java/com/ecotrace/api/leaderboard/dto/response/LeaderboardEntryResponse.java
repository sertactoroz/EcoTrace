package com.ecotrace.api.leaderboard.dto.response;

import java.util.UUID;

public record LeaderboardEntryResponse(
        long rank,
        UUID userId,
        String displayName,
        String avatarUrl,
        int level,
        long points) {}
