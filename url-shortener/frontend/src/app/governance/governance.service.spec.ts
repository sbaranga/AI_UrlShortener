import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { GovernanceService, LineageEntry, OrchestrationState } from './governance.service';
import { idleState } from './governance.fixtures';

describe('GovernanceService', () => {
  let service: GovernanceService;
  let http: HttpTestingController;

  const BASE = '/api/v1/orchestration';

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(GovernanceService);
    http = TestBed.inject(HttpTestingController);
  });

  it('reads the whole orchestration state', () => {
    let result: OrchestrationState | undefined;
    service.state().subscribe((value) => (result = value));

    const request = http.expectOne(`${BASE}/state`);
    expect(request.request.method).toBe('GET');
    request.flush(idleState());

    expect(result?.nodes).toHaveLength(6);
    expect(result?.barrier.guarded).toBe('TESTING');
    http.verify();
  });

  it('sends the limit when reading the ledger', () => {
    let entries: LineageEntry[] | undefined;
    service.lineage(50).subscribe((value) => (entries = value));

    const request = http.expectOne((candidate) => candidate.url === `${BASE}/lineage`);
    expect(request.request.params.get('limit')).toBe('50');
    request.flush([]);

    expect(entries).toEqual([]);
    http.verify();
  });

  it('posts the execution commands with no body', () => {
    service.start().subscribe();
    const start = http.expectOne(`${BASE}/start`);
    expect(start.request.method).toBe('POST');
    expect(start.request.body).toBeNull();
    start.flush(idleState());

    service.step().subscribe();
    http.expectOne(`${BASE}/step`).flush(idleState());

    service.reset().subscribe();
    http.expectOne(`${BASE}/reset`).flush(idleState());

    http.verify();
  });

  it('distinguishes a transient anomaly from a persistent one', () => {
    service.injectFailure('IMPLEMENTATION', false).subscribe();
    const transient = http.expectOne((c) => c.url === `${BASE}/failures/IMPLEMENTATION`);
    expect(transient.request.params.get('persistent')).toBe('false');
    transient.flush(idleState());

    service.injectFailure('TESTING', true).subscribe();
    const persistent = http.expectOne((c) => c.url === `${BASE}/failures/TESTING`);
    expect(persistent.request.params.get('persistent')).toBe('true');
    persistent.flush(idleState());

    http.verify();
  });

  it('sends the verification key and approver when authorising', () => {
    service.approve('RELEASE-1', 'sbaranga').subscribe();

    const request = http.expectOne(`${BASE}/approve`);
    expect(request.request.body).toEqual({ verificationKey: 'RELEASE-1', approver: 'sbaranga' });
    request.flush(idleState());

    http.verify();
  });

  it('surfaces the API error message', () => {
    let message: string | undefined;
    service.step().subscribe({ error: (error: Error) => (message = error.message) });

    http
      .expectOne(`${BASE}/step`)
      .flush(
        { status: 400, error: 'Bad Request', message: 'Start the run before stepping it' },
        { status: 400, statusText: 'Bad Request' },
      );

    expect(message).toBe('Start the run before stepping it');
    http.verify();
  });
});
