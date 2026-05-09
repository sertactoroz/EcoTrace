package com.ecotrace.api.security.jwt;

import java.time.Duration;
import java.time.Instant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class JwtBlocklistService {

    private static final String PREFIX = "jwt:blocklist:";

    private final StringRedisTemplate redis;

    public JwtBlocklistService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void revoke(String jti, Instant expiresAt) {
        Duration ttl = Duration.between(Instant.now(), expiresAt);
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        redis.opsForValue().set(PREFIX + jti, "1", ttl);
    }

    public boolean isRevoked(String jti) {
        return Boolean.TRUE.equals(redis.hasKey(PREFIX + jti));
    }
}
