import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { GovernanceComponent } from './governance.component';
import { LineageEntry, ModuleId, NodeState, OrchestrationState } from './governance.service';
import { stateWith } from './governance.fixtures';

describe('GovernanceComponent', () => {
  let fixture: ComponentFixture<GovernanceComponent>;
  let component: GovernanceComponent;
  let http: HttpTestingController;

  const BASE = '/api/v1/orchestration';

  function flushState(state: OrchestrationState): void {
    http.expectOne(`${BASE}/state`).flush(state);
  }

  function flushLineage(entries: LineageEntry[] = []): void {
    http.expectOne((candidate) => candidate.url === `${BASE}/lineage`).flush(entries);
  }

  /** The component loads state and lineage together on init and after every command. */
  function flushRefresh(state: OrchestrationState, entries: LineageEntry[] = []): void {
    flushState(state);
    flushLineage(entries);
  }

  function entry(seq: number, event: string, message: string): LineageEntry {
    return { seq, at: '2026-09-16T12:00:00Z', runId: 'aB3xY9', event, message };
  }

  beforeEach(() => {
    vi.useFakeTimers();
    let promptCall = 0;
    vi.spyOn(window, 'prompt').mockImplementation(() => (++promptCall % 2 === 1 ? 'admin' : 'change-me'));
    TestBed.configureTestingModule({
      imports: [GovernanceComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(GovernanceComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('loads the state and the ledger on init', () => {
    flushRefresh(stateWith('IDLE'), [entry(1, 'RUN_RESET', 'Engine reset')]);

    expect(component.status()).toBe('IDLE');
    expect(component.nodes()).toHaveLength(6);
    expect(component.lineage()).toHaveLength(1);
    expect(component.canStart()).toBe(true);
    expect(component.canStep()).toBe(false);
    expect(component.approvalPending()).toBe(false);
  });

  it('renders one grid cell per module carrying its state for colouring', () => {
    flushRefresh(
      stateWith('RUNNING', {
        REQUIREMENTS: 'COMPLETED',
        ARCHITECTURE: 'COMPLETED',
        IMPLEMENTATION: 'RUNNING',
        DOCUMENTATION: 'RUNNING',
      }),
    );
    fixture.detectChanges();

    const cells = fixture.nativeElement.querySelectorAll('.node');
    expect(cells).toHaveLength(6);
    const states = Array.from(cells as NodeListOf<HTMLElement>).map((cell) =>
      cell.getAttribute('data-state'),
    );
    expect(states).toEqual([
      'COMPLETED',
      'COMPLETED',
      'RUNNING',
      'RUNNING',
      'PENDING',
      'PENDING',
    ]);
  });

  it('shows the barrier holding the join until both channels arrive', () => {
    flushRefresh(
      stateWith('RUNNING', {
        REQUIREMENTS: 'COMPLETED',
        ARCHITECTURE: 'COMPLETED',
        IMPLEMENTATION: 'COMPLETED',
        DOCUMENTATION: 'RUNNING',
      }),
    );
    fixture.detectChanges();

    expect(component.state()?.barrier.open).toBe(false);
    expect(fixture.nativeElement.textContent).toContain('waiting on DOCUMENTATION');
  });

  it('polls only while the run is advancing on its own', () => {
    flushRefresh(stateWith('RUNNING', { REQUIREMENTS: 'RUNNING' }));
    expect(component.live()).toBe(true);

    vi.advanceTimersByTime(GovernanceComponent.POLL_INTERVAL_MS);
    flushRefresh(stateWith('AWAITING_APPROVAL', { REQUIREMENTS: 'COMPLETED' }));

    expect(component.live()).toBe(false);
    // Parked at the gate: nothing changes without an operator, so polling stops.
    vi.advanceTimersByTime(GovernanceComponent.POLL_INTERVAL_MS * 4);
    // http.verify() in afterEach fails if another poll fired.
  });

  it('starts a run and repaints from the command response', () => {
    flushRefresh(stateWith('IDLE'));

    component.start();

    const request = http.expectOne(`${BASE}/start`);
    expect(request.request.method).toBe('POST');
    request.flush(stateWith('RUNNING', { REQUIREMENTS: 'RUNNING' }));
    flushLineage([entry(2, 'RUN_STARTED', 'Run aB3xY9 started')]);

    expect(component.status()).toBe('RUNNING');
    expect(component.busy()).toBe(false);
    expect(component.lineage()).toHaveLength(1);
  });

  it('arms a transient anomaly and a persistent one from the failure gate', () => {
    flushRefresh(stateWith('RUNNING', { IMPLEMENTATION: 'RUNNING' }));

    component.armTransientFailure();
    const transient = http.expectOne((c) => c.url === `${BASE}/failures/IMPLEMENTATION`);
    expect(transient.request.params.get('persistent')).toBe('false');
    transient.flush(stateWith('RUNNING', {}, { armedFailures: ['IMPLEMENTATION'] }));
    flushLineage();

    expect(component.isArmed('IMPLEMENTATION')).toBe(true);

    component.selectedModule.set('TESTING');
    component.armPersistentFailure();
    const persistent = http.expectOne((c) => c.url === `${BASE}/failures/TESTING`);
    expect(persistent.request.params.get('persistent')).toBe('true');
    persistent.flush(stateWith('RUNNING', {}, { armedFailures: ['TESTING'] }));
    flushLineage();

    expect(component.isArmed('TESTING')).toBe(true);
  });

  it('offers a resume step once the run has rolled back', () => {
    flushRefresh(stateWith('ROLLED_BACK', { IMPLEMENTATION: 'FAILED' }));

    expect(component.canStep()).toBe(true);
    expect(component.live()).toBe(false);

    component.step();
    http.expectOne(`${BASE}/step`).flush(stateWith('RUNNING', { IMPLEMENTATION: 'RUNNING' }));
    flushLineage([entry(9, 'RESUMED', 'Run aB3xY9 resumed from the restored checkpoint')]);

    expect(component.status()).toBe('RUNNING');
  });

  it('refuses to authorise without a verification key and does not call the API', () => {
    flushRefresh(stateWith('AWAITING_APPROVAL', { TESTING: 'COMPLETED' }));

    component.verificationKey = '   ';
    component.approve();

    expect(component.error()).toContain('verification key');
    // http.verify() in afterEach fails if a request was made.
  });

  it('authorises the release gate and clears the key', () => {
    flushRefresh(stateWith('AWAITING_APPROVAL', { TESTING: 'COMPLETED' }));
    expect(component.approvalPending()).toBe(true);

    component.verificationKey = ' RELEASE-7 ';
    component.approver = ' sbaranga ';
    component.approve();

    const request = http.expectOne(`${BASE}/approve`);
    expect(request.request.body).toEqual({ verificationKey: 'RELEASE-7', approver: 'sbaranga' });
    request.flush(stateWith('COMPLETED', allCompleted()));
    flushLineage([entry(12, 'APPROVAL_GRANTED', 'Verification key accepted from sbaranga')]);

    expect(component.status()).toBe('COMPLETED');
    expect(component.verificationKey).toBe('');
  });

  it('surfaces a rejected command and re-reads the state', () => {
    flushRefresh(stateWith('IDLE'));

    component.step();
    http.expectOne(`${BASE}/step`).flush(
      { status: 400, error: 'Bad Request', message: 'Start the run before stepping it' },
      { status: 400, statusText: 'Bad Request' },
    );
    // The command failed, so the component re-reads rather than trusting its own copy.
    flushState(stateWith('IDLE'));

    expect(component.error()).toBe('Start the run before stepping it');
    expect(component.busy()).toBe(false);
  });

  it('reports the success rate as a percentage', () => {
    flushRefresh(
      stateWith('RUNNING', {}, {
        telemetry: {
          ...stateWith('RUNNING').telemetry,
          totalAttempts: 7,
          totalSuccesses: 6,
          totalFailures: 1,
          totalRetries: 1,
          successRate: 6 / 7,
        },
      }),
    );

    expect(component.successPercent()).toBe(86);
  });

  function allCompleted(): Partial<Record<ModuleId, NodeState>> {
    return {
      REQUIREMENTS: 'COMPLETED',
      ARCHITECTURE: 'COMPLETED',
      IMPLEMENTATION: 'COMPLETED',
      DOCUMENTATION: 'COMPLETED',
      TESTING: 'COMPLETED',
      RELEASE_READINESS: 'COMPLETED',
    };
  }

  it('drives the status pill from the run status so it can be coloured', () => {
    flushRefresh(stateWith('ROLLED_BACK', { IMPLEMENTATION: 'FAILED' }));
    fixture.detectChanges();

    const pill: HTMLElement = fixture.nativeElement.querySelector('.pill');
    expect(pill.getAttribute('data-status')).toBe('ROLLED_BACK');
    expect(pill.textContent?.trim()).toBe('ROLLED_BACK');
  });
});
