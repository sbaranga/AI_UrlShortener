import {
  ModuleId,
  NodeState,
  OrchestrationState,
  PipelineNode,
  RunStatus,
} from './governance.service';

/** Test fixtures shaped exactly like the backend's OrchestrationState. */
const MODULES: ReadonlyArray<{ id: ModuleId; name: string; channel: string; dependsOn: ModuleId[] }> = [
  { id: 'REQUIREMENTS', name: 'Requirements', channel: 'main', dependsOn: [] },
  { id: 'ARCHITECTURE', name: 'Architecture', channel: 'main', dependsOn: ['REQUIREMENTS'] },
  { id: 'IMPLEMENTATION', name: 'Implementation', channel: 'build', dependsOn: ['ARCHITECTURE'] },
  { id: 'DOCUMENTATION', name: 'Documentation', channel: 'docs', dependsOn: ['ARCHITECTURE'] },
  { id: 'TESTING', name: 'Testing', channel: 'main', dependsOn: ['DOCUMENTATION', 'IMPLEMENTATION'] },
  { id: 'RELEASE_READINESS', name: 'Release Readiness', channel: 'main', dependsOn: ['TESTING'] },
];

export function node(id: ModuleId, state: NodeState = 'PENDING'): PipelineNode {
  const definition = MODULES.find((module) => module.id === id)!;
  return {
    id: definition.id,
    name: definition.name,
    state,
    channel: definition.channel,
    dependsOn: definition.dependsOn,
    attempts: state === 'PENDING' ? 0 : 1,
    maxAttempts: 3,
    approvalGate: id === 'RELEASE_READINESS',
    join: definition.dependsOn.length > 1,
    lastDurationMs: state === 'COMPLETED' ? 900 : 0,
  };
}

export function stateWith(
  status: RunStatus,
  states: Partial<Record<ModuleId, NodeState>> = {},
  overrides: Partial<OrchestrationState> = {},
): OrchestrationState {
  const nodes = MODULES.map((module) => node(module.id, states[module.id] ?? 'PENDING'));
  const outstanding = nodes
    .filter((candidate) => candidate.id === 'IMPLEMENTATION' || candidate.id === 'DOCUMENTATION')
    .filter((candidate) => candidate.state !== 'COMPLETED')
    .map((candidate) => candidate.id);

  return {
    runId: status === 'IDLE' ? 'none' : 'aB3xY9',
    status,
    checkpoint: 'none',
    maxAttempts: 3,
    nodes,
    barrier: { guarded: 'TESTING', open: outstanding.length === 0, waitingOn: outstanding },
    approval: {
      module: 'RELEASE_READINESS',
      moduleName: 'Release Readiness',
      pending: status === 'AWAITING_APPROVAL',
    },
    armedFailures: [],
    telemetry: {
      totalAttempts: 0,
      totalSuccesses: 0,
      totalFailures: 0,
      totalRetries: 0,
      totalLatencyMs: 0,
      successRate: 1,
      modules: MODULES.map((module) => ({
        module: module.id,
        displayName: module.name,
        attempts: 0,
        successes: 0,
        failures: 0,
        retries: 0,
        lastLatencyMs: 0,
        averageLatencyMs: 0,
      })),
    },
    ...overrides,
  };
}

export function idleState(): OrchestrationState {
  return stateWith('IDLE');
}
