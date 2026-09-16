package com.example.urlshortener.model;

import com.example.urlshortener.orchestration.model.ModuleId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "telemetry_counter")
public class TelemetryCounter {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "module", nullable = false, length = 32)
    private ModuleId module;

    @Column(name = "attempts", nullable = false)
    private long attempts;

    @Column(name = "successes", nullable = false)
    private long successes;

    @Column(name = "failures", nullable = false)
    private long failures;

    @Column(name = "total_latency_ms", nullable = false)
    private long totalLatencyMs;

    @Column(name = "last_latency_ms", nullable = false)
    private long lastLatencyMs;

    protected TelemetryCounter() {}

    public TelemetryCounter(ModuleId module) {
        this.module = module;
    }

    public ModuleId getModule() {
        return module;
    }

    public long getAttempts() {
        return attempts;
    }

    public long getSuccesses() {
        return successes;
    }

    public long getFailures() {
        return failures;
    }

    public long getTotalLatencyMs() {
        return totalLatencyMs;
    }

    public long getLastLatencyMs() {
        return lastLatencyMs;
    }

    public void restore(long attempts, long successes, long failures, long totalLatencyMs, long lastLatencyMs) {
        this.attempts = attempts;
        this.successes = successes;
        this.failures = failures;
        this.totalLatencyMs = totalLatencyMs;
        this.lastLatencyMs = lastLatencyMs;
    }

    public void recordAttempt() {
        attempts++;
    }

    public void recordSuccess(long latencyMs) {
        successes++;
        totalLatencyMs += latencyMs;
        lastLatencyMs = latencyMs;
    }

    public void recordFailure() {
        failures++;
    }
}
