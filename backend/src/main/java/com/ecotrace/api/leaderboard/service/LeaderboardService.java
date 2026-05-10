package com.ecotrace.api.leaderboard.service;

import com.ecotrace.api.identity.api.UserDirectory;
import com.ecotrace.api.identity.api.UserSummary;
import com.ecotrace.api.leaderboard.api.LeaderboardScope;
import com.ecotrace.api.leaderboard.dto.response.LeaderboardEntryResponse;
import com.ecotrace.api.leaderboard.dto.response.LeaderboardResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

@Service
public class LeaderboardService {

    private static final int MAX_LIMIT = 100;

    private final StringRedisTemplate redis;
    private final UserDirectory userDirectory;

    public LeaderboardService(StringRedisTemplate redis, UserDirectory userDirectory) {
        this.redis = redis;
        this.userDirectory = userDirectory;
    }

    public LeaderboardResponse top(LeaderboardScope scope, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
        String key = LeaderboardKeys.resolve(scope, LeaderboardKeys.today());

        Set<ZSetOperations.TypedTuple<String>> tuples =
                redis.opsForZSet().reverseRangeWithScores(key, 0, safeLimit - 1L);
        return new LeaderboardResponse(scope, hydrate(tuples));
    }

    public LeaderboardEntryResponse me(LeaderboardScope scope, UUID viewerId) {
        if (viewerId == null) return null;
        String key = LeaderboardKeys.resolve(scope, LeaderboardKeys.today());
        String member = viewerId.toString();
        Long rank = redis.opsForZSet().reverseRank(key, member);
        Double score = redis.opsForZSet().score(key, member);
        if (rank == null || score == null) return null;

        Map<UUID, UserSummary> summaries = userDirectory.getSummaries(List.of(viewerId));
        UserSummary s = summaries.get(viewerId);
        return new LeaderboardEntryResponse(
                rank + 1L,
                viewerId,
                s != null ? s.displayName() : null,
                s != null ? s.avatarUrl() : null,
                s != null ? s.level() : 0,
                score.longValue());
    }

    private List<LeaderboardEntryResponse> hydrate(Set<ZSetOperations.TypedTuple<String>> tuples) {
        if (tuples == null || tuples.isEmpty()) return List.of();

        List<UUID> ids = new ArrayList<>(tuples.size());
        List<Long> scores = new ArrayList<>(tuples.size());
        for (var t : tuples) {
            if (t.getValue() == null || t.getScore() == null) continue;
            try {
                ids.add(UUID.fromString(t.getValue()));
                scores.add(t.getScore().longValue());
            } catch (IllegalArgumentException ignore) {
                // skip stray non-UUID members
            }
        }
        Map<UUID, UserSummary> summaries = userDirectory.getSummaries(ids);
        List<LeaderboardEntryResponse> out = new ArrayList<>(ids.size());
        for (int i = 0; i < ids.size(); i++) {
            UUID userId = ids.get(i);
            UserSummary s = summaries.get(userId);
            out.add(new LeaderboardEntryResponse(
                    i + 1L,
                    userId,
                    s != null ? s.displayName() : null,
                    s != null ? s.avatarUrl() : null,
                    s != null ? s.level() : 0,
                    scores.get(i)));
        }
        return out;
    }
}
