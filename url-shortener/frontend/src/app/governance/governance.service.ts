import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError } from 'rxjs';
import { toReadableError } from '../url.service';

export type NodeState = 'PENDING' | 'RUNNING' | 'COMPLETED' | 'AWAITING_APPROVAL' | 'FAILED';

export type RunStatus = 'IDLE' | 'RUNNING' | 'AWAITING_APPROVAL' | 'COMPLETED' | 'ROLLED_BACK';

export type ModuleId =
  | 'REQUIREMENTS'
  | 'ARCHITECTURE'
  | 'IMPLEMENTATION'
  | 'DOCUMENTATION'
  | 'TESTING'
  | 'RELEASE_READINESS';

export interface PipelineNode {
  id: ModuleId;
  name: string;
  state: NodeState;
  channel: string;
  dependsOn: ModuleId[];
  attempts: number;
  maxAttempts: number;
  approvalGate: boolean;
  join: boolean;
  startedAt?: string;
  finishedAt?: string;
  lastDurationMs: number;
  lastError?: string;
}

/** The synchronisation gate where the parallel channels rejoin. */
export interface BarrierStatus {
  guarded: ModuleId;
  open: boolean;
  waitingOn: ModuleId[];
}

export interface ApprovalGate {
  module: ModuleId;
  moduleName: string;
  pending: boolean;
}

export interface ModuleTelemetry {
  module: ModuleId;
  displayName: string;
  attempts: number;
  successes: number;
  failures: number;
  retries: number;
  lastLatencyMs: number;
  averageLatencyMs: number;
}

export interface TelemetrySnapshot {
  totalAttempts: number;
  totalSuccesses: number;
  totalFailures: number;
  totalRetries: number;
  totalLatencyMs: number;
  successRate: number;
  modules: ModuleTelemetry[];
}

export interface OrchestrationState {
  runId: string;
  status: RunStatus;
  startedAt?: string;
  finishedAt?: string;
  checkpoint: string;
  maxAttempts: number;
  nodes: PipelineNode[];
  barrier: BarrierStatus;
  approval: ApprovalGate;
  armedFailures: ModuleId[];
  telemetry: TelemetrySnapshot;
}

export interface LineageEntry {
  seq: number;
  at: string;
  runId: string;
  event: string;
  module?: ModuleId;
  from?: NodeState;
  to?: NodeState;
  message: string;
}

@Injectable({ providedIn: 'root' })
export class GovernanceService {
  private readonly api = '/api/v1/orchestration';
  private readonly http = inject(HttpClient);

  state(): Observable<OrchestrationState> {
    return this.http.get<OrchestrationState>(`${this.api}/state`).pipe(catchError(toReadableError));
  }

  lineage(limit = 200): Observable<LineageEntry[]> {
    return this.http
      .get<LineageEntry[]>(`${this.api}/lineage`, { params: { limit } })
      .pipe(catchError(toReadableError));
  }

  start(): Observable<OrchestrationState> {
    return this.command('start');
  }

  /** Manual execution step; also resumes a run that has been rolled back. */
  step(): Observable<OrchestrationState> {
    return this.command('step');
  }

  reset(): Observable<OrchestrationState> {
    return this.command('reset');
  }

  /**
   * @param persistent false fails one attempt so the retry recovers it, true fails every attempt
   *     and drives the automated rollback
   */
  injectFailure(module: ModuleId, persistent: boolean): Observable<OrchestrationState> {
    return this.http
      .post<OrchestrationState>(`${this.api}/failures/${module}`, null, {
        params: { persistent },
      })
      .pipe(catchError(toReadableError));
  }

  approve(verificationKey: string, approver: string): Observable<OrchestrationState> {
    return this.http
      .post<OrchestrationState>(`${this.api}/approve`, { verificationKey, approver })
      .pipe(catchError(toReadableError));
  }

  private command(path: string): Observable<OrchestrationState> {
    return this.http
      .post<OrchestrationState>(`${this.api}/${path}`, null)
      .pipe(catchError(toReadableError));
  }
}
