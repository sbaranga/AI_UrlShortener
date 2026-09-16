package com.example.urlshortener.orchestration.model;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

/**
 * One module in the graph, together with the mutable state of its current attempt.
 *
 * <p>Not thread safe by itself: every mutation happens while {@code WorkflowEngine} holds its
 * lock, which is also what makes a node's state and the graph's state consistent with each other.
 */
public final class WorkflowNode {

    private final ModuleId id;
    private final Set<ModuleId> dependsOn;
    /** Label for the parallel lane this node sits in, used by the dashboard to lay out the fork. */
    private final String channel;
    private final boolean approvalGate;

    private NodeState state = NodeState.PENDING;
    private int attempts;
    private Instant startedAt;
    private Instant finishedAt;
    private long lastDurationMs;
    private String lastError;

    public WorkflowNode(ModuleId id, Set<ModuleId> dependsOn, String channel, boolean approvalGate) {
        this.id = id;
        this.dependsOn = dependsOn.isEmpty() ? EnumSet.noneOf(ModuleId.class) : EnumSet.copyOf(dependsOn);
        this.channel = channel;
        this.approvalGate = approvalGate;
    }

    public ModuleId id() {
        return id;
    }

    public Set<ModuleId> dependsOn() {
        return Set.copyOf(dependsOn);
    }

    public String channel() {
        return channel;
    }

    /** True for the node that must not finish until a human has authorised it. */
    public boolean isApprovalGate() {
        return approvalGate;
    }

    /** True where more than one edge arrives, i.e. the node the barrier gate protects. */
    public boolean isJoin() {
        return dependsOn.size() > 1;
    }

    public NodeState state() {
        return state;
    }

    public int attempts() {
        return attempts;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public long lastDurationMs() {
        return lastDurationMs;
    }

    public String lastError() {
        return lastError;
    }

    /** Counts the attempt, so a retry is visible even while the work is still in flight. */
    public void markRunning(Instant now) {
        this.state = NodeState.RUNNING;
        this.attempts++;
        this.startedAt = now;
        this.finishedAt = null;
        this.lastError = null;
    }

    public void markAwaitingApproval(Instant now) {
        this.state = NodeState.AWAITING_APPROVAL;
        this.startedAt = now;
    }

    public void markCompleted(Instant now) {
        this.state = NodeState.COMPLETED;
        this.finishedAt = now;
        this.lastDurationMs = elapsedSince(startedAt, now);
        this.lastError = null;
    }

    public void markFailed(Instant now, String error) {
        this.state = NodeState.FAILED;
        this.finishedAt = now;
        this.lastDurationMs = elapsedSince(startedAt, now);
        this.lastError = error;
    }

    /** Returns the node to the queue for another attempt, keeping the attempt count. */
    public void markPendingRetry() {
        this.state = NodeState.PENDING;
        this.startedAt = null;
        this.finishedAt = null;
    }

    /** Clears the attempt count as well, so a resumed run gets its full retry budget back. */
    public void reset() {
        markPendingRetry();
        this.attempts = 0;
        this.lastDurationMs = 0;
        this.lastError = null;
    }

    private static long elapsedSince(Instant start, Instant now) {
        return start == null ? 0L : Math.max(0L, now.toEpochMilli() - start.toEpochMilli());
    }

    public NodeSnapshot snapshot() {
        return new NodeSnapshot(id, state, attempts, startedAt, finishedAt, lastDurationMs, lastError);
    }

    public void restore(NodeSnapshot snapshot) {
        this.state = snapshot.state();
        this.attempts = snapshot.attempts();
        this.startedAt = snapshot.startedAt();
        this.finishedAt = snapshot.finishedAt();
        this.lastDurationMs = snapshot.lastDurationMs();
        this.lastError = snapshot.lastError();
    }

    /** Immutable copy of a node's mutable fields, used as the rollback checkpoint. */
    public record NodeSnapshot(
            ModuleId id,
            NodeState state,
            int attempts,
            Instant startedAt,
            Instant finishedAt,
            long lastDurationMs,
            String lastError) {
    }
}
