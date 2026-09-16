package com.example.urlshortener.orchestration;

import com.example.urlshortener.orchestration.model.ModuleId;
import com.example.urlshortener.orchestration.model.NodeState;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Append-only record of everything the engine did: what ran, when, how state changed and what
 * failed. Kept across runs so a rollback can still be audited afterwards, and bounded so a long
 * lived process cannot grow without limit.
 */
@Component
public class LineageLedger {

    /** Event names are stable strings, so the dashboard can render them without a mapping table. */
    public static final String RUN_STARTED = "RUN_STARTED";
    public static final String RUN_RESET = "RUN_RESET";
    public static final String RUN_COMPLETED = "RUN_COMPLETED";
    public static final String CHECKPOINT_TAKEN = "CHECKPOINT_TAKEN";
    public static final String FORK_DISPATCHED = "FORK_DISPATCHED";
    public static final String NODE_STARTED = "NODE_STARTED";
    public static final String NODE_COMPLETED = "NODE_COMPLETED";
    public static final String NODE_FAILED = "NODE_FAILED";
    public static final String RETRY_SCHEDULED = "RETRY_SCHEDULED";
    public static final String RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED";
    public static final String BARRIER_WAITING = "BARRIER_WAITING";
    public static final String BARRIER_OPENED = "BARRIER_OPENED";
    public static final String ANOMALY_CAUGHT = "ANOMALY_CAUGHT";
    public static final String NODES_HALTED = "NODES_HALTED";
    public static final String ROLLBACK_RESTORED = "ROLLBACK_RESTORED";
    public static final String RESUMED = "RESUMED";
    public static final String FAILURE_ARMED = "FAILURE_ARMED";
    public static final String APPROVAL_REQUESTED = "APPROVAL_REQUESTED";
    public static final String APPROVAL_GRANTED = "APPROVAL_GRANTED";

    private final Deque<LineageEntry> entries = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();
    private final int capacity;

    public LineageLedger(OrchestrationProperties properties) {
        this.capacity = properties.getLedgerCapacity();
    }

    /**
     * @param module null for run-level events that do not belong to a single module
     * @param from previous state, null when the event did not change one
     */
    public record LineageEntry(
            long seq,
            Instant at,
            String runId,
            String event,
            ModuleId module,
            NodeState from,
            NodeState to,
            String message) {
    }

    public void record(
            String runId, String event, ModuleId module, NodeState from, NodeState to, String message) {
        LineageEntry entry = new LineageEntry(
                sequence.incrementAndGet(), Instant.now(), runId, event, module, from, to, message);
        synchronized (entries) {
            entries.addLast(entry);
            while (entries.size() > capacity) {
                entries.removeFirst();
            }
        }
    }

    public void record(String runId, String event, String message) {
        record(runId, event, null, null, null, message);
    }

    /** Oldest first, which is the order the dashboard's ledger panel prints. */
    public List<LineageEntry> entries() {
        synchronized (entries) {
            return List.copyOf(entries);
        }
    }

    /** Most recent {@code limit} entries, still oldest first. */
    public List<LineageEntry> latest(int limit) {
        List<LineageEntry> all = entries();
        if (limit <= 0 || all.size() <= limit) {
            return all;
        }
        return List.copyOf(all.subList(all.size() - limit, all.size()));
    }

    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }
}
