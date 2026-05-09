package com.ecotrace.api.leaderboard.service;

import com.ecotrace.api.identity.api.UserPointsFacade;
import com.ecotrace.api.identity.api.UserPointsSnapshot;
import com.ecotrace.api.leaderboard.api.LeaderboardScope;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
class LeaderboardBackfill {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardBackfill.class);

    private final StringRedisTemplate redis;
    private final UserPointsFacade users;

    LeaderboardBackfill(StringRedisTemplate redis, UserPointsFacade users) {
        this.redis = redis;
        this.users = users;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedGlobalIfEmpty() {
        run();
    }

    int run() {
        String key = LeaderboardKeys.resolve(LeaderboardScope.GLOBAL, LeaderboardKeys.today());
        Long existing = redis.opsForZSet().zCard(key);
        if (existing != null && existing > 0) {
            log.debug("Leaderboard '{}' already has {} entries — skipping backfill", key, existing);
            return 0;
        }

        List<UserPointsSnapshot> snapshots = users.findAllWithPoints();
        if (snapshots.isEmpty()) {
            log.info("Leaderboard backfill: no users with points yet");
            return 0;
        }

        for (UserPointsSnapshot s : snapshots) {
            redis.opsForZSet().add(key, s.userId().toString(), s.totalPoints());
        }
        log.info("Leaderboard backfill: seeded {} entries into '{}'", snapshots.size(), key);
        return snapshots.size();
    }
}
