package com.example.urlshortener.orchestration;

import com.example.urlshortener.model.TelemetryCounter;
import com.example.urlshortener.orchestration.model.ModuleId;
import com.example.urlshortener.repository.TelemetryCounterRepository;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processing latency, retry frequency and success rate per module. Counters span runs so the
 * dashboard can show whether a module is repeatedly flaky, and are reset only on an explicit reset.
 */
@Component
public class TelemetryRecorder {

    private final Map<ModuleId, Counters> counters = new EnumMap<>(ModuleId.class);
    private final TelemetryCounterRepository repository;

    private static final class Counters {
        private long attempts;
        private long successes;
        private long failures;
        private long totalLatencyMs;
        private long lastLatencyMs;
    }

    /** Constructor used by standalone workflow-engine tests. */
    public TelemetryRecorder() {
        this.repository = null;
    }

    @Autowired
    public TelemetryRecorder(TelemetryCounterRepository repository) {
        this.repository = repository;
        repository.findAll().forEach(stored -> {
            Counters counter = new Counters();
            counter.attempts = stored.getAttempts();
            counter.successes = stored.getSuccesses();
            counter.failures = stored.getFailures();
            counter.totalLatencyMs = stored.getTotalLatencyMs();
            counter.lastLatencyMs = stored.getLastLatencyMs();
            counters.put(stored.getModule(), counter);
        });
    }

    /**
     * @param attempts every execution of the module, including the ones that failed
     * @param retries attempts beyond the first, i.e. how often this module had to be re-run
     * @param averageLatencyMs mean over completed attempts only, so a failure does not skew it
     */
    public record ModuleTelemetry(
            ModuleId module,
            String displayName,
            long attempts,
            long successes,
            long failures,
            long retries,
            long lastLatencyMs,
            long averageLatencyMs) {
    }

    /**
     * @param successRate share of all attempts that completed, 1.0 when nothing has run yet
     */
    public record TelemetrySnapshot(
            long totalAttempts,
            long totalSuccesses,
            long totalFailures,
            long totalRetries,
            long totalLatencyMs,
            double successRate,
            List<ModuleTelemetry> modules) {
    }

    @Transactional
    public synchronized void recordAttempt(ModuleId module) {
        Counters counter = counters.computeIfAbsent(module, key -> new Counters());
        counter.attempts++;
        persist(module, counter);
    }

    @Transactional
    public synchronized void recordSuccess(ModuleId module, long latencyMs) {
        Counters entry = counters.computeIfAbsent(module, key -> new Counters());
        entry.successes++;
        entry.totalLatencyMs += latencyMs;
        entry.lastLatencyMs = latencyMs;
        persist(module, entry);
    }

    @Transactional
    public synchronized void recordFailure(ModuleId module) {
        Counters counter = counters.computeIfAbsent(module, key -> new Counters());
        counter.failures++;
        persist(module, counter);
    }

    @Transactional
    public synchronized void reset() {
        counters.clear();
        if (repository != null) {
            repository.deleteAllInBatch();
        }
    }

    public synchronized TelemetrySnapshot snapshot() {
        List<ModuleTelemetry> modules = new ArrayList<>();
        long totalAttempts = 0;
        long totalSuccesses = 0;
        long totalFailures = 0;
        long totalRetries = 0;
        long totalLatency = 0;

        for (ModuleId module : ModuleId.values()) {
            Counters entry = counters.getOrDefault(module, new Counters());
            // The first attempt is not a retry, so only count the ones after it.
            long retries = Math.max(0, entry.attempts - 1);
            long average = entry.successes == 0 ? 0 : entry.totalLatencyMs / entry.successes;
            modules.add(new ModuleTelemetry(
                    module,
                    module.displayName(),
                    entry.attempts,
                    entry.successes,
                    entry.failures,
                    retries,
                    entry.lastLatencyMs,
                    average));
            totalAttempts += entry.attempts;
            totalSuccesses += entry.successes;
            totalFailures += entry.failures;
            totalRetries += retries;
            totalLatency += entry.totalLatencyMs;
        }

        double successRate = totalAttempts == 0 ? 1.0 : (double) totalSuccesses / totalAttempts;
        return new TelemetrySnapshot(
                totalAttempts, totalSuccesses, totalFailures, totalRetries, totalLatency, successRate, modules);
    }

    private void persist(ModuleId module, Counters counter) {
        if (repository == null) {
            return;
        }
        TelemetryCounter stored = repository.findById(module).orElseGet(() -> new TelemetryCounter(module));
        stored.restore(
                counter.attempts,
                counter.successes,
                counter.failures,
                counter.totalLatencyMs,
                counter.lastLatencyMs);
        repository.save(stored);
    }
}
