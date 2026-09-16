package com.example.urlshortener.orchestration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app.orchestration")
public class OrchestrationProperties {

    /** Attempts per module before the engine gives up and rolls back. The spec caps this at 3. */
    @Min(1)
    @Max(3)
    private int maxAttempts = 3;

    /**
     * Simulated processing time for one module attempt. The modules stand in for real delivery
     * work, so the latency is configured rather than measured; tests set it to 0.
     */
    @Min(0)
    @Max(60_000)
    private long moduleLatencyMs = 900;

    /** Threads available to the parallel channels. Two channels fork, so 2 is the useful minimum. */
    @Min(1)
    @Max(32)
    private int workerThreads = 4;

    @Min(10)
    @Max(10_000)
    private int ledgerCapacity = 500;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public long getModuleLatencyMs() {
        return moduleLatencyMs;
    }

    public void setModuleLatencyMs(long moduleLatencyMs) {
        this.moduleLatencyMs = moduleLatencyMs;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getLedgerCapacity() {
        return ledgerCapacity;
    }

    public void setLedgerCapacity(int ledgerCapacity) {
        this.ledgerCapacity = ledgerCapacity;
    }
}
