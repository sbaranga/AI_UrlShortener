package com.example.urlshortener.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.urlshortener.config.AppProperties;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final long ONE_SECOND_NANOS = 1_000_000_000L;

    private final AtomicLong clock = new AtomicLong();

    @Test
    void allowsUpToCapacityThenRejects() {
        RateLimiter limiter = limiter(3, 60);

        assertThat(limiter.tryAcquire("ip")).isTrue();
        assertThat(limiter.tryAcquire("ip")).isTrue();
        assertThat(limiter.tryAcquire("ip")).isTrue();
        assertThat(limiter.tryAcquire("ip")).isFalse();
    }

    @Test
    void refillsOverTime() {
        RateLimiter limiter = limiter(2, 60); // one token per second
        limiter.tryAcquire("ip");
        limiter.tryAcquire("ip");
        assertThat(limiter.tryAcquire("ip")).isFalse();

        clock.addAndGet(ONE_SECOND_NANOS);

        assertThat(limiter.tryAcquire("ip")).isTrue();
        assertThat(limiter.tryAcquire("ip")).isFalse();
    }

    @Test
    void neverRefillsBeyondCapacity() {
        RateLimiter limiter = limiter(2, 60);
        clock.addAndGet(ONE_SECOND_NANOS * 600);

        assertThat(limiter.tryAcquire("ip")).isTrue();
        assertThat(limiter.tryAcquire("ip")).isTrue();
        assertThat(limiter.tryAcquire("ip")).isFalse();
    }

    @Test
    void tracksEachClientSeparately() {
        RateLimiter limiter = limiter(1, 60);

        assertThat(limiter.tryAcquire("first")).isTrue();
        assertThat(limiter.tryAcquire("first")).isFalse();
        assertThat(limiter.tryAcquire("second")).isTrue();
    }

    @Test
    void allowsEverythingWhenDisabled() {
        AppProperties.RateLimit config = config(1, 60);
        config.setEnabled(false);
        RateLimiter limiter = new RateLimiter(config, clock::get);

        assertThat(limiter.tryAcquire("ip")).isTrue();
        assertThat(limiter.tryAcquire("ip")).isTrue();
    }

    @Test
    void reportsAtLeastOneSecondOfRetryDelay() {
        assertThat(limiter(30, 6000).retryAfterSeconds()).isEqualTo(1);
        assertThat(limiter(30, 30).retryAfterSeconds()).isEqualTo(2);
    }

    private RateLimiter limiter(int capacity, int refillPerMinute) {
        return new RateLimiter(config(capacity, refillPerMinute), clock::get);
    }

    private AppProperties.RateLimit config(int capacity, int refillPerMinute) {
        AppProperties.RateLimit config = new AppProperties.RateLimit();
        config.setCapacity(capacity);
        config.setRefillPerMinute(refillPerMinute);
        return config;
    }
}
