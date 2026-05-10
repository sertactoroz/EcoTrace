package com.ecotrace.api.leaderboard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ecotrace.api.gamification.event.PointsAwarded;
import com.ecotrace.api.identity.api.UserDirectory;
import com.ecotrace.api.identity.api.UserSummary;
import com.ecotrace.api.leaderboard.api.LeaderboardScope;
import com.ecotrace.api.leaderboard.dto.response.LeaderboardEntryResponse;
import com.ecotrace.api.leaderboard.dto.response.LeaderboardResponse;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
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

class LeaderboardIntegrationTest {

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private final FakeDirectory directory = new FakeDirectory();
    private LeaderboardService service;
    private PointsAwardedListener listener;

    @BeforeAll
    static void startRedis() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Skipping leaderboard integration test — Docker/Podman socket not reachable");
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
    void wireServices() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        service = new LeaderboardService(redis, directory);
        listener = new PointsAwardedListener(redis);
        directory.summaries.clear();
    }

    @Test
    void listener_writes_global_zset_and_service_returns_ranked_entries() {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID carol = UUID.randomUUID();
        directory.put(alice, "Alice", 2);
        directory.put(bob, "Bob", 3);
        directory.put(carol, "Carol", 1);

        listener.on(award(alice, 50, 50));
        listener.on(award(bob, 80, 80));
        listener.on(award(carol, 20, 20));

        LeaderboardResponse top = service.top(LeaderboardScope.GLOBAL, 10);

        assertThat(top.entries()).hasSize(3);
        assertThat(top.entries().get(0).userId()).isEqualTo(bob);
        assertThat(top.entries().get(0).rank()).isEqualTo(1L);
        assertThat(top.entries().get(0).points()).isEqualTo(80L);
        assertThat(top.entries().get(0).displayName()).isEqualTo("Bob");

        assertThat(top.entries().get(1).userId()).isEqualTo(alice);
        assertThat(top.entries().get(2).userId()).isEqualTo(carol);

        LeaderboardEntryResponse me = service.me(LeaderboardScope.GLOBAL, alice);
        assertThat(me).isNotNull();
        assertThat(me.userId()).isEqualTo(alice);
        assertThat(me.rank()).isEqualTo(2L);
        assertThat(me.points()).isEqualTo(50L);
    }

    @Test
    void global_uses_absolute_total_weekly_accumulates_delta() {
        UUID user = UUID.randomUUID();
        directory.put(user, "User", 1);

        listener.on(award(user, 30, 30));   // first award: total 30
        listener.on(award(user, 25, 55));   // second: total 55, delta 25

        LeaderboardResponse global = service.top(LeaderboardScope.GLOBAL, 10);
        assertThat(global.entries().get(0).points()).isEqualTo(55L);

        LeaderboardResponse weekly = service.top(LeaderboardScope.WEEKLY, 10);
        // Two awards, deltas 30 + 25 → 55 (sum)
        assertThat(weekly.entries().get(0).points()).isEqualTo(55L);
    }

    @Test
    void zero_delta_award_is_skipped() {
        UUID user = UUID.randomUUID();
        directory.put(user, "User", 1);

        listener.on(award(user, 0, 0));

        LeaderboardResponse global = service.top(LeaderboardScope.GLOBAL, 10);
        assertThat(global.entries()).isEmpty();
    }

    @Test
    void respects_limit() {
        for (int i = 0; i < 5; i++) {
            UUID id = UUID.randomUUID();
            directory.put(id, "U" + i, 1);
            listener.on(award(id, 10 + i, 10 + i));
        }

        LeaderboardResponse top = service.top(LeaderboardScope.GLOBAL, 2);
        assertThat(top.entries()).hasSize(2);
        assertThat(top.entries().get(0).points()).isEqualTo(14L);
        assertThat(top.entries().get(1).points()).isEqualTo(13L);
    }

    @Test
    void me_is_null_when_viewer_not_in_zset() {
        UUID alice = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        directory.put(alice, "Alice", 1);
        listener.on(award(alice, 40, 40));

        LeaderboardResponse top = service.top(LeaderboardScope.GLOBAL, 10);
        assertThat(top.entries()).hasSize(1);

        LeaderboardEntryResponse me = service.me(LeaderboardScope.GLOBAL, stranger);
        assertThat(me).isNull();
    }

    private static PointsAwarded award(UUID userId, int delta, long total) {
        return new PointsAwarded(
                UUID.randomUUID(), userId, UUID.randomUUID(), UUID.randomUUID(),
                delta, total, 1, "COLLECTION", OffsetDateTime.now());
    }

    private static final class FakeDirectory implements UserDirectory {
        final Map<UUID, UserSummary> summaries = new HashMap<>();

        void put(UUID id, String name, int level) {
            summaries.put(id, new UserSummary(id, name, null, level));
        }

        @Override
        public Map<UUID, UserSummary> getSummaries(Collection<UUID> userIds) {
            Map<UUID, UserSummary> out = new HashMap<>();
            for (UUID id : userIds) {
                UserSummary s = summaries.get(id);
                if (s != null) out.put(id, s);
            }
            return out;
        }
    }
}
