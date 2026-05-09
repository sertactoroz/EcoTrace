package com.ecotrace.api.leaderboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecotrace.api.identity.api.UserPointsFacade;
import com.ecotrace.api.identity.api.UserPointsSnapshot;
import com.ecotrace.api.identity.api.UserSummary;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class LeaderboardBackfillTest {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void startRedis() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Skipping backfill integration test — Docker/Podman socket not reachable");
        REDIS.start();
        var cfg = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        factory = new LettuceConnectionFactory(cfg);
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stopRedis() {
        if (factory != null) factory.destroy();
        if (REDIS.isRunning()) REDIS.stop();
    }

    @BeforeEach
    void flush() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
    }

    @Test
    void seeds_global_zset_from_facade() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        FakeFacade facade = new FakeFacade(List.of(
                new UserPointsSnapshot(a, 200L),
                new UserPointsSnapshot(b, 50L)));

        int seeded = new LeaderboardBackfill(redis, facade).run();

        assertThat(seeded).isEqualTo(2);
        Long card = redis.opsForZSet().zCard("lb:global");
        assertThat(card).isEqualTo(2L);
        assertThat(redis.opsForZSet().score("lb:global", a.toString())).isEqualTo(200d);
        assertThat(redis.opsForZSet().score("lb:global", b.toString())).isEqualTo(50d);
    }

    @Test
    void skips_when_zset_already_populated() {
        UUID existing = UUID.randomUUID();
        redis.opsForZSet().add("lb:global", existing.toString(), 999d);

        FakeFacade facade = new FakeFacade(List.of(
                new UserPointsSnapshot(UUID.randomUUID(), 100L)));

        int seeded = new LeaderboardBackfill(redis, facade).run();

        assertThat(seeded).isZero();
        assertThat(redis.opsForZSet().zCard("lb:global")).isEqualTo(1L);
        assertThat(redis.opsForZSet().score("lb:global", existing.toString())).isEqualTo(999d);
    }

    @Test
    void no_users_is_a_noop() {
        FakeFacade facade = new FakeFacade(List.of());

        int seeded = new LeaderboardBackfill(redis, facade).run();

        assertThat(seeded).isZero();
        assertThat(redis.opsForZSet().zCard("lb:global")).isZero();
    }

    private static final class FakeFacade implements UserPointsFacade,
            com.ecotrace.api.identity.api.UserDirectory {
        private final List<UserPointsSnapshot> snapshots;

        FakeFacade(List<UserPointsSnapshot> snapshots) {
            this.snapshots = new ArrayList<>(snapshots);
        }

        @Override
        public long getTotalPoints(UUID userId) {
            return snapshots.stream().filter(s -> s.userId().equals(userId))
                    .mapToLong(UserPointsSnapshot::totalPoints).findFirst().orElse(0L);
        }

        @Override
        public void setPointsAndLevel(UUID userId, long totalPoints, int level) {
            // not needed
        }

        @Override
        public List<UserPointsSnapshot> findAllWithPoints() {
            return List.copyOf(snapshots);
        }

        @Override
        public Map<UUID, UserSummary> getSummaries(Collection<UUID> userIds) {
            return Map.of();
        }
    }
}
