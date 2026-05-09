package com.ecotrace.api.identity.service;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenStore {

    private static final String PREFIX = "refresh:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    public RefreshTokenStore(StringRedisTemplate redis, ObjectMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    public void store(String jti, RefreshRecord record, Duration ttl) {
        try {
            redis.opsForValue().set(PREFIX + jti, mapper.writeValueAsString(record), ttl);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist refresh token", e);
        }
    }

    public Optional<RefreshRecord> find(String jti) {
        String json = redis.opsForValue().get(PREFIX + jti);
        if (json == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(json, RefreshRecord.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public void delete(String jti) {
        redis.delete(PREFIX + jti);
    }

    public record RefreshRecord(
            @JsonProperty("userId") UUID userId,
            @JsonProperty("deviceId") String deviceId,
            @JsonProperty("expiresAt") Instant expiresAt) {

        @JsonCreator
        public RefreshRecord {}
    }
}
