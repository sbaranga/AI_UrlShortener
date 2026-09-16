package com.example.urlshortener.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisUrlCache {

    private static final String KEY_PREFIX = "short-url:";

    private final StringRedisTemplate redisTemplate;

    public RedisUrlCache(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Optional<String> get(String shortCode) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key(shortCode)));
        } catch (DataAccessException exception) {
            return Optional.empty();
        }
    }

    public void put(String shortCode, String target, Instant expiresAt) {
        try {
            if (expiresAt == null) {
                redisTemplate.opsForValue().set(key(shortCode), target);
                return;
            }
            Duration ttl = Duration.between(Instant.now(), expiresAt);
            if (!ttl.isZero() && !ttl.isNegative()) {
                redisTemplate.opsForValue().set(key(shortCode), target, ttl);
            }
        } catch (DataAccessException exception) {
            // Redis is an optimization; the database remains the source of truth.
        }
    }

    public void evict(String shortCode) {
        try {
            redisTemplate.delete(key(shortCode));
        } catch (DataAccessException exception) {
            // A failed eviction must not make a successful database delete fail.
        }
    }

    private String key(String shortCode) {
        return KEY_PREFIX + shortCode;
    }
}