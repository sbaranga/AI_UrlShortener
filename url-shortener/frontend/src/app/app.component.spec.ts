import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { AppComponent } from './app.component';
import { AnalyticsSummary, Page, ShortLink } from './url.service';

describe('AppComponent dashboard', () => {
  let fixture: ComponentFixture<AppComponent>;
  let component: AppComponent;
  let http: HttpTestingController;

  const linkAt = (index: number): ShortLink => ({
    shortCode: `code${index}`,
    shortUrl: `http://localhost:8080/code${index}`,
    longUrl: `https://example.com/${index}`,
    createdAt: '2026-01-01T00:00:00Z',
    clickCount: 0,
  });

  const fullPage = (size: number) => Array.from({ length: size }, (_, index) => linkAt(index));

  /** Answers the pending GET /api/v1/analytics, asserting which page and size were asked for. */
  function flushList(expected: { page: number; size: number }, body: Partial<Page<ShortLink>> = {}): void {
    const request = http.expectOne(
      (candidate) => candidate.url === '/api/v1/analytics' && candidate.method === 'GET',
    );
    expect(request.request.params.get('page')).toBe(String(expected.page));
    expect(request.request.params.get('size')).toBe(String(expected.size));

    const size = body.size ?? expected.size;
    const totalItems = body.totalItems ?? 0;
    request.flush({
      items: body.items ?? [],
      page: body.page ?? expected.page,
      size,
      totalItems,
      totalPages: body.totalPages ?? Math.ceil(totalItems / size),
    } satisfies Page<ShortLink>);
  }

  function flushSummary(body: Partial<AnalyticsSummary> = {}): void {
    http.expectOne('/api/v1/analytics/summary').flush({
      totalLinks: body.totalLinks ?? 0,
      totalClicks: body.totalClicks ?? 0,
      activeLinks: body.activeLinks ?? 0,
      expiredLinks: body.expiredLinks ?? 0,
      ...(body.mostClicked ? { mostClicked: body.mostClicked } : {}),
    } satisfies AnalyticsSummary);
  }

  /** The initial load: the component asks for page 1 and the totals together. */
  function flushInitialLoad(): void {
    flushList({ page: 0, size: 10 }, { items: fullPage(10), totalItems: 25 });
    flushSummary({ totalLinks: 25 });
  }

  beforeEach(() => {
    // Fake timers let the polling interval be driven deterministically; the component subscribes
    // to it in its constructor, so they have to be installed before the component is created.
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(AppComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  it('requests the first page and the totals on load', () => {
    flushList({ page: 0, size: 10 }, { items: fullPage(10), totalItems: 25 });
    flushSummary({ totalLinks: 25, totalClicks: 9, activeLinks: 24, expiredLinks: 1 });

    expect(component.page()).toBe(0);
    expect(component.pageCount()).toBe(3);
    expect(component.rangeStart()).toBe(1);
    expect(component.rangeEnd()).toBe(10);
    expect(component.hasPrevious()).toBe(false);
    expect(component.hasNext()).toBe(true);
    expect(component.summary()?.totalClicks).toBe(9);
    expect(component.lastUpdated()).not.toBeNull();
  });

  it('walks forward and back through the pages', () => {
    flushInitialLoad();

    component.goToNext();
    flushList({ page: 1, size: 10 }, { items: fullPage(10), totalItems: 25 });
    expect(component.page()).toBe(1);
    expect(component.rangeStart()).toBe(11);
    expect(component.rangeEnd()).toBe(20);

    component.goToLast();
    flushList({ page: 2, size: 10 }, { items: fullPage(5), totalItems: 25 });
    expect(component.rangeStart()).toBe(21);
    expect(component.rangeEnd()).toBe(25);
    expect(component.hasNext()).toBe(false);

    component.goToPrevious();
    flushList({ page: 1, size: 10 }, { items: fullPage(10), totalItems: 25 });
    expect(component.page()).toBe(1);

    component.goToFirst();
    flushList({ page: 0, size: 10 }, { items: fullPage(10), totalItems: 25 });
    expect(component.page()).toBe(0);
  });

  it('does not request a page outside the range', () => {
    flushInitialLoad();

    component.goToPrevious();
    component.goToPage(3);
    component.goToPage(0);

    // http.verify() in afterEach fails if any of those issued a request.
    expect(component.page()).toBe(0);
  });

  it('restarts at the first page when the page size changes', () => {
    flushInitialLoad();
    component.goToNext();
    flushList({ page: 1, size: 10 }, { items: fullPage(10), totalItems: 25 });

    component.changePageSize({ target: { value: '50' } } as unknown as Event);

    flushList({ page: 0, size: 50 }, { items: fullPage(25), totalItems: 25 });
    expect(component.page()).toBe(0);
    expect(component.pageSize()).toBe(50);
    expect(component.pageCount()).toBe(1);
  });

  it('steps back when the current page no longer exists after a delete', () => {
    flushList({ page: 0, size: 10 }, { items: fullPage(10), totalItems: 11 });
    flushSummary({ totalLinks: 11 });
    component.goToNext();
    flushList({ page: 1, size: 10 }, { items: [linkAt(10)], totalItems: 11 });
    expect(component.page()).toBe(1);

    vi.spyOn(window, 'prompt').mockReturnValueOnce('admin').mockReturnValueOnce('change-me');
    component.remove(linkAt(10));
    http.expectOne('/api/v1/urls/code10').flush(null);

    // The refresh lands on an empty page 2, so the component retries the last page with rows.
    flushList({ page: 1, size: 10 }, { items: [], totalItems: 10, totalPages: 1 });
    flushList({ page: 0, size: 10 }, { items: fullPage(10), totalItems: 10 });
    flushSummary({ totalLinks: 10 });

    expect(component.page()).toBe(0);
    expect(component.links().length).toBe(10);
  });

  it('jumps to the first page after creating a link', () => {
    flushInitialLoad();
    component.goToNext();
    flushList({ page: 1, size: 10 }, { items: fullPage(10), totalItems: 25 });

    component.form.patchValue({ url: 'https://example.com/new' });
    vi.spyOn(window, 'prompt').mockReturnValueOnce('admin').mockReturnValueOnce('change-me');
    component.submit();
    http.expectOne('/api/v1/shorten').flush(linkAt(99));

    flushList({ page: 0, size: 10 }, { items: fullPage(10), totalItems: 26 });
    flushSummary({ totalLinks: 26 });
    expect(component.page()).toBe(0);
  });

  it('re-reads the current page and the totals on each poll', () => {
    flushInitialLoad();
    component.goToNext();
    flushList({ page: 1, size: 10 }, { items: fullPage(10), totalItems: 25 });

    vi.advanceTimersByTime(AppComponent.POLL_INTERVAL_MS);

    // The poll stays on the page the user is looking at.
    flushList({ page: 1, size: 10 }, { items: fullPage(10), totalItems: 26 });
    flushSummary({ totalLinks: 26, totalClicks: 4 });

    expect(component.page()).toBe(1);
    expect(component.totalItems()).toBe(26);
    expect(component.summary()?.totalClicks).toBe(4);
    // A background poll must not put the Refresh button into its loading state.
    expect(component.loading()).toBe(false);
  });

  it('stops polling while live updates are switched off', () => {
    flushInitialLoad();

    component.toggleLiveUpdates();
    expect(component.liveUpdates()).toBe(false);

    vi.advanceTimersByTime(AppComponent.POLL_INTERVAL_MS * 3);
    // http.verify() in afterEach fails if a poll fired.
  });

  it('refreshes immediately when live updates are switched back on', () => {
    flushInitialLoad();
    component.toggleLiveUpdates();

    component.toggleLiveUpdates();

    expect(component.liveUpdates()).toBe(true);
    flushList({ page: 0, size: 10 }, { items: fullPage(10), totalItems: 25 });
    flushSummary({ totalLinks: 25 });
  });
});
