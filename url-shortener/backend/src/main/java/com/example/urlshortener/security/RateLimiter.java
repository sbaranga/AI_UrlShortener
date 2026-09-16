package com.example.urlshortener.security;

import com.example.urlshortener.config.AppProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * In-memory token bucket keyed by client. Each key gets {@code capacity} tokens that refill at
 * {@code refillPerMinute}, so short bursts are allowed but the sustained rate is capped.
 *
 * <p>State lives in this JVM only: behind more than one instance each replica enforces its own
 * quota. A shared store (Redis) would be needed for a real cluster.
 */
@Component
public class RateLimiter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final AppProperties.RateLimit config;
    private final LongSupplier nanoTime;

    @Autowired
    public RateLimiter(AppProperties properties) {
        this(properties.getRateLimit(), System::nanoTime);
    }

    /** Visible for tests, which drive time forward without sleeping. */
    RateLimiter(AppProperties.RateLimit config, LongSupplier nanoTime) {
        this.config = config;
        this.nanoTime = nanoTime;
    }

    /** @return true if the request is allowed and a token was consumed. */
    public boolean tryAcquire(String key) {
        if (!config.isEnabled()) {
            return true;
        }
        long now = nanoTime.getAsLong();
        evictIdleIfCrowded(now);
        Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket(config.getCapacity(), now));
        return bucket.tryConsume(config.getCapacity(), refillPerSecond(), now);
    }

    /** Whole seconds a caller should wait before retrying; always at least 1. */
    public long retryAfterSeconds() {
        return Math.max(1, (long) Math.ceil(1.0 / refillPerSecond()));
    }

    private double refillPerSecond() {
        return config.getRefillPerMinute() / 60.0;
    }

    /**
     * Buckets accumulate one entry per distinct client, so drop the ones that have been idle long
     * enough to have refilled completely. Only runs once the map grows past the configured cap,
     * which keeps the common path a single map lookup.
     */
    private void evictIdleIfCrowded(long now) {
        if (buckets.size() <= config.getMaxTrackedClients()) {
            return;
        }
        long idleNanosForFullRefill = (long) (config.getCapacity() / refillPerSecond() * 1_000_000_000L);
        buckets.values().removeIf(bucket -> now - bucket.lastSeenNanos() > idleNanosForFullRefill);
    }

    private static final class Bucket {

        private double tokens;
        private long lastRefillNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
        }

        synchronized boolean tryConsume(int capacity, double refillPerSecond, long now) {
            double elapsedSeconds = Math.max(0, now - lastRefillNanos) / 1_000_000_000.0;
            tokens = Math.min(capacity, tokens + elapsedSeconds * refillPerSecond);
            lastRefillNanos = now;
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }

        synchronized long lastSeenNanos() {
            return lastRefillNanos;
        }
    }
}
