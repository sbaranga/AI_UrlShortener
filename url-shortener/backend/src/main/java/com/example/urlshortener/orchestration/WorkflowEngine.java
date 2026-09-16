package com.example.urlshortener.orchestration;

import com.example.urlshortener.exception.ApiException;
import com.example.urlshortener.orchestration.dto.ApprovalView;
import com.example.urlshortener.orchestration.dto.NodeView;
import com.example.urlshortener.orchestration.dto.OrchestrationState;
import com.example.urlshortener.orchestration.model.ModuleId;
import com.example.urlshortener.orchestration.model.NodeState;
import com.example.urlshortener.orchestration.model.RunStatus;
import com.example.urlshortener.orchestration.model.SynchronizationBarrier;
import com.example.urlshortener.orchestration.model.WorkflowGraph;
import com.example.urlshortener.orchestration.model.WorkflowNode;
import com.example.urlshortener.orchestration.model.WorkflowNode.NodeSnapshot;
import com.example.urlshortener.util.Base62Encoder;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Stateful, non-linear workflow engine for the six delivery modules.
 *
 * <p>Routing is driven entirely by the graph: after every state change the engine re-computes the
 * set of nodes whose dependencies are satisfied and dispatches all of them, which is what makes the
 * fork run its channels concurrently and the barrier hold the join back until both arrive.
 *
 * <p>All state transitions happen under {@link #lock}. Worker tasks capture the run
 * {@code generation} when they are dispatched and discard their result if it has moved on, which is
 * how a rollback halts work that is already in flight: the thread finishes its simulated work but
 * can no longer touch the graph.
 */
@Service
public class WorkflowEngine {

    private final OrchestrationProperties properties;
    private final LineageLedger ledger;
    private final TelemetryRecorder telemetry;
    private final Executor executor;

    private final ReentrantLock lock = new ReentrantLock();
    private final SecureRandom random = new SecureRandom();
    /** Module to "fails every attempt": false fires once, true keeps failing until retries run out. */
    private final Map<ModuleId, Boolean> armedFailures = new EnumMap<>(ModuleId.class);

    private WorkflowGraph graph = WorkflowGraph.deliveryPipeline();
    private RunStatus status = RunStatus.IDLE;
    private String runId = "none";
    private Instant startedAt;
    private Instant finishedAt;
    private List<NodeSnapshot> checkpoint = List.of();
    private String checkpointLabel = "none";
    private long generation;

    public WorkflowEngine(
            OrchestrationProperties properties,
            LineageLedger ledger,
            TelemetryRecorder telemetry,
            Executor orchestrationExecutor) {
        this.properties = properties;
        this.ledger = ledger;
        this.telemetry = telemetry;
        this.executor = orchestrationExecutor;
    }

    // ---------------------------------------------------------------- commands

    /** Starts a fresh run. Telemetry counters are kept, so flaky modules stay visible across runs. */
    public OrchestrationState start() {
        lock.lock();
        try {
            if (status == RunStatus.RUNNING || status == RunStatus.AWAITING_APPROVAL) {
                throw ApiException.conflict("A run is already in progress; reset it first");
            }
            generation++;
            graph = WorkflowGraph.deliveryPipeline();
            armedFailures.clear();
            runId = newRunId();
            status = RunStatus.RUNNING;
            startedAt = Instant.now();
            finishedAt = null;
            checkpoint = List.of();
            checkpointLabel = "none";
            ledger.record(runId, LineageLedger.RUN_STARTED, "Run " + runId + " started with 6 modules pending");
            advance();
            return stateInternal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Manual execution step: dispatches whatever the graph currently allows. After a rollback this
     * is what resumes the run from the restored checkpoint.
     */
    public OrchestrationState step() {
        lock.lock();
        try {
            switch (status) {
                case IDLE -> throw ApiException.badRequest("Start the run before stepping it");
                case COMPLETED -> throw ApiException.badRequest("Run " + runId + " has already completed");
                case AWAITING_APPROVAL -> throw ApiException.badRequest(
                        "Run is awaiting approval; authorise the release gate to continue");
                case ROLLED_BACK -> resume();
                case RUNNING -> advance();
            }
            return stateInternal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Arms an anomaly for {@code module}. It fires when that module's current or next attempt
     * finishes, which is what drives the retry and rollback paths from the dashboard.
     *
     * @param persistent false to fail a single attempt, so the bounded retry recovers it; true to
     *     fail every attempt, which exhausts the budget and triggers the automated rollback
     */
    public OrchestrationState injectFailure(ModuleId module, boolean persistent) {
        lock.lock();
        try {
            if (status == RunStatus.IDLE) {
                throw ApiException.badRequest("Start the run before simulating a failure");
            }
            WorkflowNode node = graph.node(module);
            if (node.state() == NodeState.COMPLETED) {
                throw ApiException.badRequest(module.displayName() + " has already completed");
            }
            armedFailures.put(module, persistent);
            ledger.record(
                    runId,
                    LineageLedger.FAILURE_ARMED,
                    module,
                    node.state(),
                    node.state(),
                    (persistent ? "Persistent" : "Transient") + " anomaly armed for "
                            + module.displayName()
                            + (persistent
                                    ? "; it will fail every attempt and force a rollback"
                                    : "; it will fail its next attempt only"));
            return stateInternal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Processes the human verification key that releases the final gate. The key itself is never
     * written to the ledger; only the fact that one was accepted, and by whom.
     */
    public OrchestrationState approve(String verificationKey, String approver) {
        lock.lock();
        try {
            if (status != RunStatus.AWAITING_APPROVAL) {
                throw ApiException.badRequest("No approval is pending");
            }
            if (!StringUtils.hasText(verificationKey)) {
                throw ApiException.badRequest("verificationKey is required to release the gate");
            }
            WorkflowNode node = graph.approvalNode();
            String who = StringUtils.hasText(approver) ? approver.trim() : "unidentified operator";
            ledger.record(
                    runId,
                    LineageLedger.APPROVAL_GRANTED,
                    node.id(),
                    NodeState.AWAITING_APPROVAL,
                    NodeState.RUNNING,
                    "Verification key accepted from " + who + "; releasing " + node.id().displayName());
            status = RunStatus.RUNNING;
            node.markPendingRetry();
            dispatch(node);
            return stateInternal();
        } finally {
            lock.unlock();
        }
    }

    /** Returns the graph to all-pending and clears telemetry. The ledger keeps its history. */
    public OrchestrationState reset() {
        lock.lock();
        try {
            generation++;
            String previous = runId;
            graph = WorkflowGraph.deliveryPipeline();
            armedFailures.clear();
            status = RunStatus.IDLE;
            runId = "none";
            startedAt = null;
            finishedAt = null;
            checkpoint = List.of();
            checkpointLabel = "none";
            telemetry.reset();
            ledger.record(
                    previous,
                    LineageLedger.RUN_RESET,
                    "Engine reset after run " + previous + "; all modules returned to PENDING");
            return stateInternal();
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- queries

    public OrchestrationState state() {
        lock.lock();
        try {
            return stateInternal();
        } finally {
            lock.unlock();
        }
    }

    // ---------------------------------------------------------------- routing

    /**
     * Re-evaluates the graph and dispatches every node that is now runnable. More than one node in
     * the ready set means the routing has forked; the barrier is consulted before the join is let
     * through so the synchronisation point is an explicit step rather than an implicit one.
     */
    private void advance() {
        if (status != RunStatus.RUNNING) {
            return;
        }
        if (graph.allCompleted()) {
            complete();
            return;
        }

        List<WorkflowNode> ready = graph.readyNodes();
        if (ready.isEmpty()) {
            SynchronizationBarrier.Status barrier = SynchronizationBarrier.evaluate(graph);
            if (!barrier.open() && graph.node(barrier.guarded()).state() == NodeState.PENDING) {
                ledger.record(
                        runId,
                        LineageLedger.BARRIER_WAITING,
                        barrier.guarded(),
                        NodeState.PENDING,
                        NodeState.PENDING,
                        "Barrier holding " + barrier.guarded().displayName() + "; waiting on "
                                + describe(barrier.waitingOn()));
            }
            return;
        }

        takeCheckpoint(ready);

        if (ready.size() > 1) {
            ledger.record(
                    runId,
                    LineageLedger.FORK_DISPATCHED,
                    "Routing forked into " + ready.size() + " parallel channels: " + describeNodes(ready));
        }
        for (WorkflowNode node : ready) {
            if (SynchronizationBarrier.releases(graph, node)) {
                ledger.record(
                        runId,
                        LineageLedger.BARRIER_OPENED,
                        node.id(),
                        NodeState.PENDING,
                        NodeState.PENDING,
                        "Barrier opened for " + node.id().displayName() + "; all channels arrived");
            }
            if (node.isApprovalGate()) {
                requestApproval(node);
                continue;
            }
            dispatch(node);
        }
    }

    private void dispatch(WorkflowNode node) {
        NodeState from = node.state();
        node.markRunning(Instant.now());
        telemetry.recordAttempt(node.id());
        ledger.record(
                runId,
                LineageLedger.NODE_STARTED,
                node.id(),
                from,
                NodeState.RUNNING,
                node.id().displayName() + " started (attempt " + node.attempts() + " of "
                        + properties.getMaxAttempts() + ")");

        ModuleId id = node.id();
        long dispatchedGeneration = generation;
        executor.execute(() -> runModule(id, dispatchedGeneration));
    }

    /** Runs on a worker thread: simulates the module's work, then hands the outcome back. */
    private void runModule(ModuleId id, long dispatchedGeneration) {
        long latency = properties.getModuleLatencyMs();
        if (latency > 0) {
            try {
                Thread.sleep(latency);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        settle(id, dispatchedGeneration);
    }

    private void settle(ModuleId id, long dispatchedGeneration) {
        lock.lock();
        try {
            // A rollback or reset moved the run on; this result is stale and must not be applied.
            if (dispatchedGeneration != generation) {
                return;
            }
            WorkflowNode node = graph.node(id);
            if (node.state() != NodeState.RUNNING) {
                return;
            }
            Boolean persistent = armedFailures.get(id);
            if (persistent == null) {
                succeed(node);
                return;
            }
            // A transient anomaly is spent once it has fired; a persistent one stays armed.
            if (!persistent) {
                armedFailures.remove(id);
            }
            failAttempt(node, "Injected anomaly during " + id.displayName());
        } finally {
            lock.unlock();
        }
    }

    private void succeed(WorkflowNode node) {
        NodeState from = node.state();
        node.markCompleted(Instant.now());
        telemetry.recordSuccess(node.id(), node.lastDurationMs());
        ledger.record(
                runId,
                LineageLedger.NODE_COMPLETED,
                node.id(),
                from,
                NodeState.COMPLETED,
                node.id().displayName() + " completed in " + node.lastDurationMs() + "ms");
        advance();
    }

    /** Applies one failed attempt: retry while the bound allows it, otherwise roll the run back. */
    private void failAttempt(WorkflowNode node, String error) {
        NodeState from = node.state();
        node.markFailed(Instant.now(), error);
        telemetry.recordFailure(node.id());
        int maxAttempts = properties.getMaxAttempts();
        ledger.record(
                runId,
                LineageLedger.NODE_FAILED,
                node.id(),
                from,
                NodeState.FAILED,
                error + " (attempt " + node.attempts() + " of " + maxAttempts + ")");

        if (node.attempts() < maxAttempts) {
            node.markPendingRetry();
            ledger.record(
                    runId,
                    LineageLedger.RETRY_SCHEDULED,
                    node.id(),
                    NodeState.FAILED,
                    NodeState.PENDING,
                    "Retrying " + node.id().displayName() + ", attempt " + (node.attempts() + 1) + " of "
                            + maxAttempts);
            dispatch(node);
            return;
        }

        ledger.record(
                runId,
                LineageLedger.RETRIES_EXHAUSTED,
                node.id(),
                NodeState.FAILED,
                NodeState.FAILED,
                node.id().displayName() + " exhausted all " + maxAttempts + " attempts");
        rollback(node);
    }

    /**
     * Global anomaly handling: stop anything still in flight and put the graph back to the
     * checkpoint taken before the current stage. The offending module is deliberately left FAILED
     * so the dashboard still shows where the run broke.
     */
    private void rollback(WorkflowNode failed) {
        ledger.record(
                runId,
                LineageLedger.ANOMALY_CAUGHT,
                failed.id(),
                NodeState.FAILED,
                NodeState.FAILED,
                "Anomaly caught globally in " + failed.id().displayName() + ": " + failed.lastError());

        // Moving the generation on is what actually halts the workers: their results are discarded.
        generation++;

        List<ModuleId> halted = new ArrayList<>();
        for (WorkflowNode node : graph.nodes()) {
            if (node.state() == NodeState.RUNNING) {
                halted.add(node.id());
                node.markPendingRetry();
            }
        }
        ledger.record(
                runId,
                LineageLedger.NODES_HALTED,
                halted.isEmpty()
                        ? "No other nodes were active when the anomaly was caught"
                        : "Halted active nodes: " + describe(halted));

        graph.restore(checkpoint, Set.of(failed.id()));
        armedFailures.clear();
        status = RunStatus.ROLLED_BACK;
        finishedAt = Instant.now();
        ledger.record(
                runId,
                LineageLedger.ROLLBACK_RESTORED,
                "Restored checkpoint taken before " + checkpointLabel + "; run rolled back");
    }

    /** Clears the failure and continues from the restored checkpoint. */
    private void resume() {
        status = RunStatus.RUNNING;
        finishedAt = null;
        for (WorkflowNode node : graph.nodes()) {
            if (node.state() == NodeState.FAILED) {
                node.reset();
            }
        }
        ledger.record(runId, LineageLedger.RESUMED, "Run " + runId + " resumed from the restored checkpoint");
        advance();
    }

    private void requestApproval(WorkflowNode node) {
        NodeState from = node.state();
        node.markAwaitingApproval(Instant.now());
        status = RunStatus.AWAITING_APPROVAL;
        ledger.record(
                runId,
                LineageLedger.APPROVAL_REQUESTED,
                node.id(),
                from,
                NodeState.AWAITING_APPROVAL,
                node.id().displayName() + " is blocked pending a human verification key");
    }

    private void complete() {
        status = RunStatus.COMPLETED;
        finishedAt = Instant.now();
        ledger.record(runId, LineageLedger.RUN_COMPLETED, "Run " + runId + " completed all 6 modules");
    }

    /** Snapshots the graph before a stage is dispatched, so a rollback has somewhere to return to. */
    private void takeCheckpoint(List<WorkflowNode> stage) {
        checkpoint = graph.snapshot();
        checkpointLabel = describeNodes(stage);
        ledger.record(
                runId,
                LineageLedger.CHECKPOINT_TAKEN,
                "Checkpoint taken before " + checkpointLabel);
    }

    // ---------------------------------------------------------------- helpers

    private OrchestrationState stateInternal() {
        List<NodeView> nodes = graph.nodes().stream()
                .map(node -> NodeView.from(node, properties.getMaxAttempts()))
                .toList();
        WorkflowNode approvalNode = graph.approvalNode();
        return new OrchestrationState(
                runId,
                status,
                startedAt,
                finishedAt,
                checkpointLabel,
                properties.getMaxAttempts(),
                nodes,
                SynchronizationBarrier.evaluate(graph),
                new ApprovalView(
                        approvalNode.id(),
                        approvalNode.id().displayName(),
                        status == RunStatus.AWAITING_APPROVAL),
                List.copyOf(armedFailures.keySet()),
                telemetry.snapshot());
    }

    private String newRunId() {
        return Base62Encoder.encodePadded(random.nextLong(Base62Encoder.capacityFor(6)), 6);
    }

    private static String describe(List<ModuleId> modules) {
        return modules.stream().map(ModuleId::displayName).reduce((a, b) -> a + ", " + b).orElse("nothing");
    }

    private static String describeNodes(List<WorkflowNode> nodes) {
        return describe(nodes.stream().map(WorkflowNode::id).toList());
    }
}
