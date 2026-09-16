package com.example.urlshortener.orchestration.model;

import java.util.List;

/**
 * The gate that binds the parallel channels back together. The engine asks the barrier whether a
 * node may be released rather than testing dependencies inline, so the join is an explicit step in
 * the routing rather than a side effect of the dependency check.
 */
public final class SynchronizationBarrier {

    private SynchronizationBarrier() {
    }

    /**
     * @param open true when every incoming channel has completed
     * @param waitingOn the channels still outstanding, empty once the gate is open
     */
    public record Status(ModuleId guarded, boolean open, List<ModuleId> waitingOn) {
    }

    public static Status evaluate(WorkflowGraph graph) {
        WorkflowNode join = graph.joinNode();
        List<ModuleId> outstanding = graph.outstandingDependencies(join);
        return new Status(join.id(), outstanding.isEmpty(), outstanding);
    }

    /** True when {@code node} is the guarded join and all of its channels have arrived. */
    public static boolean releases(WorkflowGraph graph, WorkflowNode node) {
        return node.isJoin() && graph.dependenciesSatisfied(node);
    }
}
