package com.example.urlshortener.orchestration.model;

import com.example.urlshortener.orchestration.model.WorkflowNode.NodeSnapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The delivery pipeline as a directed acyclic graph:
 *
 * <pre>
 *   Requirements -&gt; Architecture -&gt; fork -&gt; Implementation --\
 *                                        \-&gt; Documentation --+-&gt; [barrier] Testing -&gt; Release Readiness
 * </pre>
 *
 * <p>Implementation and Documentation form two channels that run at the same time; Testing has two
 * incoming edges and so is only released once the barrier gate opens.
 */
public final class WorkflowGraph {

    public static final String CHANNEL_MAIN = "main";
    public static final String CHANNEL_BUILD = "build";
    public static final String CHANNEL_DOCS = "docs";

    private final Map<ModuleId, WorkflowNode> nodes;

    private WorkflowGraph(Map<ModuleId, WorkflowNode> nodes) {
        this.nodes = nodes;
    }

    /** Builds a fresh graph with every module back at {@link NodeState#PENDING}. */
    public static WorkflowGraph deliveryPipeline() {
        Map<ModuleId, WorkflowNode> nodes = new EnumMap<>(ModuleId.class);
        nodes.put(
                ModuleId.REQUIREMENTS,
                new WorkflowNode(ModuleId.REQUIREMENTS, Set.of(), CHANNEL_MAIN, false));
        nodes.put(
                ModuleId.ARCHITECTURE,
                new WorkflowNode(ModuleId.ARCHITECTURE, Set.of(ModuleId.REQUIREMENTS), CHANNEL_MAIN, false));
        nodes.put(
                ModuleId.IMPLEMENTATION,
                new WorkflowNode(
                        ModuleId.IMPLEMENTATION, Set.of(ModuleId.ARCHITECTURE), CHANNEL_BUILD, false));
        nodes.put(
                ModuleId.DOCUMENTATION,
                new WorkflowNode(ModuleId.DOCUMENTATION, Set.of(ModuleId.ARCHITECTURE), CHANNEL_DOCS, false));
        nodes.put(
                ModuleId.TESTING,
                new WorkflowNode(
                        ModuleId.TESTING,
                        Set.of(ModuleId.IMPLEMENTATION, ModuleId.DOCUMENTATION),
                        CHANNEL_MAIN,
                        false));
        nodes.put(
                ModuleId.RELEASE_READINESS,
                new WorkflowNode(ModuleId.RELEASE_READINESS, Set.of(ModuleId.TESTING), CHANNEL_MAIN, true));
        return new WorkflowGraph(nodes);
    }

    public WorkflowNode node(ModuleId id) {
        WorkflowNode node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("no such module: " + id);
        }
        return node;
    }

    /** Nodes in declaration order, which is also the order the dashboard grid renders them. */
    public Collection<WorkflowNode> nodes() {
        return nodes.values();
    }

    /**
     * Nodes that are pending and whose every dependency has completed. More than one entry means
     * the routing has forked and those nodes are meant to run concurrently.
     */
    public List<WorkflowNode> readyNodes() {
        List<WorkflowNode> ready = new ArrayList<>();
        for (WorkflowNode node : nodes.values()) {
            if (node.state() == NodeState.PENDING && dependenciesSatisfied(node)) {
                ready.add(node);
            }
        }
        return ready;
    }

    public boolean dependenciesSatisfied(WorkflowNode node) {
        return outstandingDependencies(node).isEmpty();
    }

    /** Dependencies of {@code node} that have not completed yet. */
    public List<ModuleId> outstandingDependencies(WorkflowNode node) {
        List<ModuleId> outstanding = new ArrayList<>();
        for (ModuleId dependency : node.dependsOn()) {
            if (node(dependency).state() != NodeState.COMPLETED) {
                outstanding.add(dependency);
            }
        }
        outstanding.sort(null);
        return outstanding;
    }

    public boolean allCompleted() {
        return nodes.values().stream().allMatch(node -> node.state() == NodeState.COMPLETED);
    }

    public boolean anyRunning() {
        return nodes.values().stream().anyMatch(node -> node.state() == NodeState.RUNNING);
    }

    /** The node the barrier gate protects, i.e. the one where the parallel channels rejoin. */
    public WorkflowNode joinNode() {
        return nodes.values().stream()
                .filter(WorkflowNode::isJoin)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("pipeline has no join node"));
    }

    public WorkflowNode approvalNode() {
        return nodes.values().stream()
                .filter(WorkflowNode::isApprovalGate)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("pipeline has no approval gate"));
    }

    public List<NodeSnapshot> snapshot() {
        return nodes.values().stream().map(WorkflowNode::snapshot).toList();
    }

    /** Restores every node from {@code snapshot}, skipping any module in {@code except}. */
    public void restore(List<NodeSnapshot> snapshot, Set<ModuleId> except) {
        for (NodeSnapshot entry : snapshot) {
            if (!except.contains(entry.id())) {
                node(entry.id()).restore(entry);
            }
        }
    }
}
