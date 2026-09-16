package com.example.urlshortener.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.urlshortener.exception.ApiException;
import com.example.urlshortener.orchestration.ai.AiProperties;
import com.example.urlshortener.orchestration.ai.AiProvider;
import com.example.urlshortener.orchestration.ai.AiTask;
import com.example.urlshortener.orchestration.LineageLedger.LineageEntry;
import com.example.urlshortener.orchestration.dto.NodeView;
import com.example.urlshortener.orchestration.dto.OrchestrationState;
import com.example.urlshortener.orchestration.model.ModuleId;
import com.example.urlshortener.orchestration.model.NodeState;
import com.example.urlshortener.orchestration.model.RunStatus;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkflowEngineTest {

    /**
     * Executor that queues instead of running, so a test can advance the graph one dispatch at a
     * time and observe RUNNING states that a real pool would race through.
     */
    private static final class QueuedExecutor implements Executor {

        private final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            queue.addLast(command);
        }

        int pending() {
            return queue.size();
        }

        /** Runs the oldest queued task; tasks dispatched by it land at the back of the queue. */
        boolean runNext() {
            Runnable next = queue.pollFirst();
            if (next == null) {
                return false;
            }
            next.run();
            return true;
        }

        /**
         * Runs the newest queued task instead. A real pool interleaves the channels freely, and
         * running one channel's retries while the other's task is still waiting is the only way to
         * reach a rollback with work genuinely in flight.
         */
        boolean runLast() {
            Runnable last = queue.pollLast();
            if (last == null) {
                return false;
            }
            last.run();
            return true;
        }

        void drain() {
            while (runNext()) {
                // Each completion can enqueue the next stage, so keep going until nothing is left.
            }
        }
    }

    private QueuedExecutor executor;
    private LineageLedger ledger;
    private TelemetryRecorder telemetry;
    private WorkflowEngine engine;

    @BeforeEach
    void setUp() {
        OrchestrationProperties properties = new OrchestrationProperties();
        // No simulated latency: the test drives time by running queued tasks.
        properties.setModuleLatencyMs(0);
        executor = new QueuedExecutor();
        ledger = new LineageLedger(properties);
        telemetry = new TelemetryRecorder();
        engine = new WorkflowEngine(properties, ledger, telemetry, executor);
    }

    private NodeState stateOf(OrchestrationState state, ModuleId module) {
        return state.nodes().stream()
                .filter(node -> node.id() == module)
                .map(NodeView::state)
                .findFirst()
                .orElseThrow();
    }

    private List<String> events() {
        return ledger.entries().stream().map(LineageEntry::event).toList();
    }

    @Test
    void startsWithEveryModulePendingUntilTheFirstDispatch() {
        OrchestrationState idle = engine.state();

        assertThat(idle.status()).isEqualTo(RunStatus.IDLE);
        assertThat(idle.nodes()).hasSize(6);
        assertThat(idle.nodes()).allSatisfy(node -> assertThat(node.state()).isEqualTo(NodeState.PENDING));
        assertThat(idle.maxAttempts()).isEqualTo(3);
    }

    @Test
    void routesSequentialStagesInDependencyOrder() {
        OrchestrationState started = engine.start();

        assertThat(started.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(stateOf(started, ModuleId.REQUIREMENTS)).isEqualTo(NodeState.RUNNING);
        assertThat(stateOf(started, ModuleId.ARCHITECTURE)).isEqualTo(NodeState.PENDING);
        assertThat(executor.pending()).isEqualTo(1);

        executor.runNext();
        OrchestrationState afterRequirements = engine.state();
        assertThat(stateOf(afterRequirements, ModuleId.REQUIREMENTS)).isEqualTo(NodeState.COMPLETED);
        assertThat(stateOf(afterRequirements, ModuleId.ARCHITECTURE)).isEqualTo(NodeState.RUNNING);
    }

    @Test
    void forksImplementationAndDocumentationIntoConcurrentChannels() {
        engine.start();
        executor.runNext(); // Requirements
        executor.runNext(); // Architecture

        // Both channels are dispatched before either runs, which is what makes them concurrent.
        assertThat(executor.pending()).isEqualTo(2);
        OrchestrationState forked = engine.state();
        assertThat(stateOf(forked, ModuleId.IMPLEMENTATION)).isEqualTo(NodeState.RUNNING);
        assertThat(stateOf(forked, ModuleId.DOCUMENTATION)).isEqualTo(NodeState.RUNNING);
        assertThat(events()).contains(LineageLedger.FORK_DISPATCHED);

        List<String> channels = forked.nodes().stream()
                .filter(node -> node.id() == ModuleId.IMPLEMENTATION || node.id() == ModuleId.DOCUMENTATION)
                .map(NodeView::channel)
                .toList();
        assertThat(channels).doesNotHaveDuplicates();
    }

    @Test
    void barrierHoldsTestingUntilBothChannelsArrive() {
        engine.start();
        executor.runNext(); // Requirements
        executor.runNext(); // Architecture
        executor.runNext(); // Implementation only

        OrchestrationState halfWay = engine.state();
        assertThat(stateOf(halfWay, ModuleId.IMPLEMENTATION)).isEqualTo(NodeState.COMPLETED);
        assertThat(stateOf(halfWay, ModuleId.DOCUMENTATION)).isEqualTo(NodeState.RUNNING);
        assertThat(stateOf(halfWay, ModuleId.TESTING)).isEqualTo(NodeState.PENDING);
        assertThat(halfWay.barrier().guarded()).isEqualTo(ModuleId.TESTING);
        assertThat(halfWay.barrier().open()).isFalse();
        assertThat(halfWay.barrier().waitingOn()).containsExactly(ModuleId.DOCUMENTATION);
        assertThat(events()).contains(LineageLedger.BARRIER_WAITING);

        executor.runNext(); // Documentation arrives

        OrchestrationState released = engine.state();
        assertThat(released.barrier().open()).isTrue();
        assertThat(stateOf(released, ModuleId.TESTING)).isEqualTo(NodeState.RUNNING);
        assertThat(events()).contains(LineageLedger.BARRIER_OPENED);
    }

    @Test
    void blocksTheFinalTransitionUntilAVerificationKeyIsProcessed() {
        engine.start();
        executor.drain();

        OrchestrationState gated = engine.state();
        assertThat(gated.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(stateOf(gated, ModuleId.RELEASE_READINESS)).isEqualTo(NodeState.AWAITING_APPROVAL);
        assertThat(gated.approval().pending()).isTrue();
        assertThat(gated.approval().module()).isEqualTo(ModuleId.RELEASE_READINESS);
        // Nothing is queued: the gate stops the run rather than running and waiting.
        assertThat(executor.pending()).isZero();
        assertThat(events()).contains(LineageLedger.APPROVAL_REQUESTED);

        assertThatThrownBy(() -> engine.approve("  ", "operator"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("verificationKey is required");
        assertThatThrownBy(() -> engine.step())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("awaiting approval");

        engine.approve("RELEASE-KEY-9", "sbaranga");
        executor.drain();

        OrchestrationState done = engine.state();
        assertThat(done.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(done.nodes()).allSatisfy(node -> assertThat(node.state()).isEqualTo(NodeState.COMPLETED));
        assertThat(events()).contains(LineageLedger.APPROVAL_GRANTED, LineageLedger.RUN_COMPLETED);
    }

    @Test
    void neverWritesTheVerificationKeyToTheLedger() {
        engine.start();
        executor.drain();

        engine.approve("SUPER-SECRET-KEY", "sbaranga");

        assertThat(ledger.entries()).noneSatisfy(entry -> assertThat(entry.message()).contains("SUPER-SECRET-KEY"));
        assertThat(ledger.entries())
                .anySatisfy(entry -> assertThat(entry.message()).contains("key accepted from sbaranga"));
    }

    @Test
    void recoversATransientAnomalyWithinTheRetryBudget() {
        engine.start();
        engine.injectFailure(ModuleId.REQUIREMENTS, false);

        executor.runNext(); // the armed attempt fails and a retry is dispatched

        OrchestrationState afterRetry = engine.state();
        assertThat(afterRetry.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(stateOf(afterRetry, ModuleId.REQUIREMENTS)).isEqualTo(NodeState.RUNNING);
        assertThat(events()).contains(LineageLedger.NODE_FAILED, LineageLedger.RETRY_SCHEDULED);

        executor.drain();

        assertThat(engine.state().status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(stateOf(engine.state(), ModuleId.REQUIREMENTS)).isEqualTo(NodeState.COMPLETED);
        assertThat(telemetry.snapshot().totalRetries()).isEqualTo(1);
    }

    @Test
    void capsRetriesAtThreeAttemptsPerModule() {
        engine.start();
        engine.injectFailure(ModuleId.REQUIREMENTS, true);

        executor.drain();

        OrchestrationState rolledBack = engine.state();
        assertThat(stateOf(rolledBack, ModuleId.REQUIREMENTS)).isEqualTo(NodeState.FAILED);
        assertThat(rolledBack.nodes().stream()
                        .filter(node -> node.id() == ModuleId.REQUIREMENTS)
                        .findFirst()
                        .orElseThrow()
                        .attempts())
                .isEqualTo(3);
        assertThat(events()).contains(LineageLedger.RETRIES_EXHAUSTED);
    }

    @Test
    void rollsBackGloballyAndRestoresTheCheckpointWhenRetriesAreExhausted() {
        engine.start();
        executor.runNext(); // Requirements
        executor.runNext(); // Architecture -> forks the two channels

        // Fail one channel persistently while the other is still in flight.
        engine.injectFailure(ModuleId.IMPLEMENTATION, true);
        executor.drain();

        OrchestrationState rolledBack = engine.state();
        assertThat(rolledBack.status()).isEqualTo(RunStatus.ROLLED_BACK);
        assertThat(stateOf(rolledBack, ModuleId.IMPLEMENTATION)).isEqualTo(NodeState.FAILED);
        // Documentation completed after the fork, so the checkpoint puts it back to pending.
        assertThat(stateOf(rolledBack, ModuleId.DOCUMENTATION)).isEqualTo(NodeState.PENDING);
        // Work from before the checkpoint is left alone.
        assertThat(stateOf(rolledBack, ModuleId.REQUIREMENTS)).isEqualTo(NodeState.COMPLETED);
        assertThat(stateOf(rolledBack, ModuleId.ARCHITECTURE)).isEqualTo(NodeState.COMPLETED);
        assertThat(stateOf(rolledBack, ModuleId.TESTING)).isEqualTo(NodeState.PENDING);
        assertThat(events())
                .contains(
                        LineageLedger.ANOMALY_CAUGHT,
                        LineageLedger.NODES_HALTED,
                        LineageLedger.ROLLBACK_RESTORED);
    }

    @Test
    void haltsNodesStillInFlightAndDiscardsTheirResults() {
        engine.start();
        executor.runNext(); // Requirements
        executor.runNext(); // Architecture -> both channels dispatched, both queued

        // Burn the documentation channel's retries while the implementation task is still waiting.
        engine.injectFailure(ModuleId.DOCUMENTATION, true);
        executor.runLast();
        executor.runLast();
        executor.runLast();

        OrchestrationState rolledBack = engine.state();
        assertThat(rolledBack.status()).isEqualTo(RunStatus.ROLLED_BACK);
        assertThat(stateOf(rolledBack, ModuleId.DOCUMENTATION)).isEqualTo(NodeState.FAILED);
        // Implementation was running when the anomaly was caught, so it was halted back to pending.
        assertThat(stateOf(rolledBack, ModuleId.IMPLEMENTATION)).isEqualTo(NodeState.PENDING);
        assertThat(ledger.entries())
                .anySatisfy(entry -> {
                    assertThat(entry.event()).isEqualTo(LineageLedger.NODES_HALTED);
                    assertThat(entry.message()).contains("Implementation");
                });

        // Its task was dispatched before the rollback and only runs now; it must be discarded.
        assertThat(executor.pending()).isEqualTo(1);
        executor.drain();

        OrchestrationState after = engine.state();
        assertThat(after.status()).isEqualTo(RunStatus.ROLLED_BACK);
        assertThat(stateOf(after, ModuleId.IMPLEMENTATION)).isEqualTo(NodeState.PENDING);
    }

    @Test
    void resumesFromTheRestoredCheckpointOnAManualStep() {
        engine.start();
        engine.injectFailure(ModuleId.REQUIREMENTS, true);
        executor.drain();
        assertThat(engine.state().status()).isEqualTo(RunStatus.ROLLED_BACK);

        OrchestrationState resumed = engine.step();

        assertThat(resumed.status()).isEqualTo(RunStatus.RUNNING);
        // The failed module gets its full budget back rather than resuming at attempt 3.
        assertThat(stateOf(resumed, ModuleId.REQUIREMENTS)).isEqualTo(NodeState.RUNNING);
        assertThat(events()).contains(LineageLedger.RESUMED);

        executor.drain();
        assertThat(engine.state().status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
    }

    @Test
    void refusesToStartASecondRunWhileOneIsInProgress() {
        engine.start();

        assertThatThrownBy(() -> engine.start())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already in progress");
    }

    @Test
    void rejectsCommandsThatDoNotApplyToTheCurrentStatus() {
        assertThatThrownBy(() -> engine.step())
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Start the run");
        assertThatThrownBy(() -> engine.injectFailure(ModuleId.TESTING, false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Start the run");
        assertThatThrownBy(() -> engine.approve("KEY", "operator"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No approval is pending");

        engine.start();
        executor.runNext();

        assertThatThrownBy(() -> engine.injectFailure(ModuleId.REQUIREMENTS, false))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already completed");
    }

    @Test
    void resetReturnsTheGraphToPendingButKeepsTheLedgerHistory() {
        engine.start();
        executor.drain();
        int entriesBeforeReset = ledger.entries().size();

        OrchestrationState reset = engine.reset();

        assertThat(reset.status()).isEqualTo(RunStatus.IDLE);
        assertThat(reset.nodes()).allSatisfy(node -> assertThat(node.state()).isEqualTo(NodeState.PENDING));
        assertThat(reset.telemetry().totalAttempts()).isZero();
        assertThat(ledger.entries()).hasSizeGreaterThan(entriesBeforeReset);
        assertThat(events()).contains(LineageLedger.RUN_RESET);
    }

    @Test
    void recordsLatencyRetryAndSuccessTelemetryPerModule() {
        engine.start();
        engine.injectFailure(ModuleId.DOCUMENTATION, false);
        executor.drain();
        engine.approve("KEY-1", "operator");
        executor.drain();

        TelemetryRecorder.TelemetrySnapshot snapshot = telemetry.snapshot();
        assertThat(snapshot.totalSuccesses()).isEqualTo(6);
        assertThat(snapshot.totalFailures()).isEqualTo(1);
        // 6 modules plus the one retried attempt.
        assertThat(snapshot.totalAttempts()).isEqualTo(7);
        assertThat(snapshot.successRate()).isEqualTo(6.0 / 7.0);
        assertThat(snapshot.modules()).hasSize(6);
        assertThat(snapshot.modules()).allSatisfy(module -> assertThat(module.successes()).isEqualTo(1));
    }

    @Test
    void ledgerRecordsStateTransitionsWithTimestamps() {
        engine.start();
        executor.runNext();

        List<LineageEntry> entries = ledger.entries();
        assertThat(entries).isNotEmpty();
        assertThat(entries).allSatisfy(entry -> {
            assertThat(entry.at()).isNotNull();
            assertThat(entry.seq()).isPositive();
            assertThat(entry.event()).isNotBlank();
            assertThat(entry.message()).isNotBlank();
        });
        // Sequence numbers are strictly increasing, so the panel can print in a stable order.
        assertThat(entries.stream().map(LineageEntry::seq).toList()).isSorted();
        assertThat(entries)
                .anySatisfy(entry -> {
                    assertThat(entry.event()).isEqualTo(LineageLedger.NODE_COMPLETED);
                    assertThat(entry.module()).isEqualTo(ModuleId.REQUIREMENTS);
                    assertThat(entry.from()).isEqualTo(NodeState.RUNNING);
                    assertThat(entry.to()).isEqualTo(NodeState.COMPLETED);
                });
    }

    @Test
    void ledgerIsBoundedByItsConfiguredCapacity() {
        OrchestrationProperties tiny = new OrchestrationProperties();
        tiny.setLedgerCapacity(10);
        LineageLedger bounded = new LineageLedger(tiny);

        for (int i = 0; i < 25; i++) {
            bounded.record("run", LineageLedger.NODE_STARTED, "entry " + i);
        }

        assertThat(bounded.entries()).hasSize(10);
        assertThat(bounded.entries().get(9).message()).isEqualTo("entry 24");
    }

    @Test
    void enabledAiProviderRunsForEachDispatchedModule() {
        OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
        orchestrationProperties.setModuleLatencyMs(0);
        AiProperties aiProperties = new AiProperties();
        aiProperties.setEnabled(true);
        List<AiTask> tasks = new java.util.ArrayList<>();
        AiProvider provider = tasks::add;
        WorkflowEngine aiEngine = new WorkflowEngine(
                orchestrationProperties, ledger, telemetry, executor, provider, aiProperties);

        aiEngine.start();
        executor.drain();

        assertThat(tasks).extracting(AiTask::module).containsExactly(
                ModuleId.REQUIREMENTS,
                ModuleId.ARCHITECTURE,
                ModuleId.IMPLEMENTATION,
                ModuleId.DOCUMENTATION,
                ModuleId.TESTING);
    }
}
