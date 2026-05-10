package com.ecotrace.api.leaderboard.service;

import com.ecotrace.api.gamification.event.PointsAwarded;
import com.ecotrace.api.leaderboard.api.LeaderboardScope;
import java.time.Duration;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
class PointsAwardedListener {

    private static final Logger log = LoggerFactory.getLogger(PointsAwardedListener.class);

    private final StringRedisTemplate redis;

    PointsAwardedListener(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void on(PointsAwarded event) {
        if (event.delta() == 0) return;

        String member = event.userId().toString();
        LocalDate today = LeaderboardKeys.today();

        try {
            for (LeaderboardScope scope : LeaderboardScope.values()) {
                String key = LeaderboardKeys.resolve(scope, today);
                if (scope == LeaderboardScope.GLOBAL) {
                    redis.opsForZSet().add(key, member, event.newTotalPoints());
                } else {
                    redis.opsForZSet().incrementScore(key, member, event.delta());
                    Duration ttl = LeaderboardKeys.ttlFor(scope);
                    if (!ttl.isZero()) {
                        redis.expire(key, ttl);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Failed to update leaderboard for user {} (tx {}): {}",
                    event.userId(), event.transactionId(), e.toString());
        }
    }
}
