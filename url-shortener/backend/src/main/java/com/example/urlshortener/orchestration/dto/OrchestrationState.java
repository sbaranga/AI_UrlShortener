package com.example.urlshortener.orchestration.dto;

import com.example.urlshortener.orchestration.TelemetryRecorder.TelemetrySnapshot;
import com.example.urlshortener.orchestration.model.ModuleId;
import com.example.urlshortener.orchestration.model.RunStatus;
import com.example.urlshortener.orchestration.model.SynchronizationBarrier;
import java.time.Instant;
import java.util.List;

/**
 * Everything the dashboard needs in one response, so the grid, the barrier indicator, the gate and
 * the telemetry panel can never disagree with each other.
 *
 * @param checkpoint label of the stage the rollback checkpoint was taken before
 * @param armedFailures modules with a simulated anomaly waiting to fire
 */
public record OrchestrationState(
        String runId,
        RunStatus status,
        Instant startedAt,
        Instant finishedAt,
        String checkpoint,
        int maxAttempts,
        List<NodeView> nodes,
        SynchronizationBarrier.Status barrier,
        ApprovalView approval,
        List<ModuleId> armedFailures,
        TelemetrySnapshot telemetry) {
}
