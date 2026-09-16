package com.example.urlshortener.orchestration.dto;

import com.example.urlshortener.orchestration.model.ModuleId;
import com.example.urlshortener.orchestration.model.NodeState;
import com.example.urlshortener.orchestration.model.WorkflowNode;
import java.time.Instant;
import java.util.List;

/**
 * One grid cell on the dashboard: the module, the state that drives its colour, and enough
 * telemetry to explain why it is in that state.
 */
public record NodeView(
        ModuleId id,
        String name,
        NodeState state,
        String channel,
        List<ModuleId> dependsOn,
        int attempts,
        int maxAttempts,
        boolean approvalGate,
        boolean join,
        Instant startedAt,
        Instant finishedAt,
        long lastDurationMs,
        String lastError) {

    public static NodeView from(WorkflowNode node, int maxAttempts) {
        List<ModuleId> dependsOn = node.dependsOn().stream().sorted().toList();
        return new NodeView(
                node.id(),
                node.id().displayName(),
                node.state(),
                node.channel(),
                dependsOn,
                node.attempts(),
                maxAttempts,
                node.isApprovalGate(),
                node.isJoin(),
                node.startedAt(),
                node.finishedAt(),
                node.lastDurationMs(),
                node.lastError());
    }
}
