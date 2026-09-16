import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Observable, interval } from 'rxjs';
import {
  GovernanceService,
  LineageEntry,
  ModuleId,
  OrchestrationState,
  PipelineNode,
} from './governance.service';

@Component({
  selector: 'app-governance',
  imports: [DatePipe, DecimalPipe, FormsModule],
  templateUrl: './governance.component.html',
  styleUrl: './governance.component.css',
})
export class GovernanceComponent {
  private readonly governance = inject(GovernanceService);

  /**
   * Modules take about a second each, so this is fast enough to see a cell change colour without
   * flooding the API. Only an actively RUNNING graph is polled; every other status only moves in
   * response to a button, and is refreshed by that command's own response.
   */
  static readonly POLL_INTERVAL_MS = 1_500;

  readonly state = signal<OrchestrationState | null>(null);
  readonly lineage = signal<LineageEntry[]>([]);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly lastUpdated = signal<Date | null>(null);

  /** Target of the two failure-injection gates. */
  readonly selectedModule = signal<ModuleId>('IMPLEMENTATION');
  verificationKey = '';
  approver = '';

  readonly status = computed(() => this.state()?.status ?? 'IDLE');
  readonly nodes = computed<PipelineNode[]>(() => this.state()?.nodes ?? []);
  readonly approvalPending = computed(() => this.state()?.approval.pending ?? false);
  /** A run in RUNNING advances on its own; anything else waits for the operator. */
  readonly live = computed(() => this.status() === 'RUNNING');
  readonly canStart = computed(() => this.status() !== 'RUNNING' && this.status() !== 'AWAITING_APPROVAL');
  readonly canStep = computed(() => this.status() === 'RUNNING' || this.status() === 'ROLLED_BACK');

  /** Success rate as a whole-number percentage for the telemetry tile. */
  readonly successPercent = computed(() => Math.round((this.state()?.telemetry.successRate ?? 1) * 100));

  constructor() {
    this.refresh();
    interval(GovernanceComponent.POLL_INTERVAL_MS)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        if (this.live() && !this.busy()) {
          this.refresh();
        }
      });
  }

  refresh(): void {
    this.governance.state().subscribe({
      next: (state) => this.applyState(state),
      error: (err: Error) => this.error.set(err.message),
    });
    this.governance.lineage().subscribe({
      next: (entries) => this.lineage.set(entries),
      error: (err: Error) => this.error.set(err.message),
    });
  }

  start(): void {
    this.withCredentials((username, password) => this.run(this.governance.start(username, password)));
  }

  step(): void {
    this.withCredentials((username, password) => this.run(this.governance.step(username, password)));
  }

  reset(): void {
    this.withCredentials((username, password) => this.run(this.governance.reset(username, password)));
  }

  /** Fails one attempt, so the bounded retry is seen recovering the module. */
  armTransientFailure(): void {
    this.withCredentials((username, password) =>
      this.run(this.governance.injectFailure(this.selectedModule(), false, username, password)),
    );
  }

  /** Fails every attempt, so the retry budget is exhausted and the rollback runs. */
  armPersistentFailure(): void {
    this.withCredentials((username, password) =>
      this.run(this.governance.injectFailure(this.selectedModule(), true, username, password)),
    );
  }

  approve(): void {
    if (!this.verificationKey.trim()) {
      this.error.set('Enter a verification key to release the gate.');
      return;
    }
    this.withCredentials((username, password) =>
      this.run(this.governance.approve(this.verificationKey.trim(), this.approver.trim(), username, password)),
    );
    this.verificationKey = '';
  }

  selectModule(event: Event): void {
    this.selectedModule.set((event.target as HTMLSelectElement).value as ModuleId);
  }

  dismissError(): void {
    this.error.set(null);
  }

  isArmed(module: ModuleId): boolean {
    return (this.state()?.armedFailures ?? []).includes(module);
  }

  /** Nodes sharing a lane with another node, i.e. the ones the fork runs concurrently. */
  isParallel(node: PipelineNode): boolean {
    return this.nodes().filter((other) => other.channel === node.channel).length === 1;
  }

  private run(command: Observable<OrchestrationState>): void {
    this.busy.set(true);
    this.error.set(null);
    command.subscribe({
      next: (state) => {
        this.applyState(state);
        this.busy.set(false);
        // The command moved the graph, so pull the records it just wrote.
        this.governance.lineage().subscribe({
          next: (entries) => this.lineage.set(entries),
          error: (err: Error) => this.error.set(err.message),
        });
      },
      error: (err: Error) => {
        this.error.set(err.message);
        this.busy.set(false);
        // Re-read the state: the command failed, but the run may have moved on regardless.
        this.governance.state().subscribe({
          next: (state) => this.applyState(state),
          error: () => undefined,
        });
      },
    });
  }

  private applyState(state: OrchestrationState): void {
    this.state.set(state);
    this.lastUpdated.set(new Date());
  }

  private withCredentials(action: (username: string, password: string) => void): void {
    const username = window.prompt('Username required for governance actions:');
    if (username === null) {
      return;
    }
    const password = window.prompt('Password required for governance actions:');
    if (password === null) {
      return;
    }
    action(username, password);
  }
}
