package com.ecotrace.api.leaderboard.dto.response;

import com.ecotrace.api.leaderboard.api.LeaderboardScope;
import java.util.List;

public record LeaderboardResponse(
        LeaderboardScope scope,
        List<LeaderboardEntryResponse> entries,
        LeaderboardEntryResponse me) {}
