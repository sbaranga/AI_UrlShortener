import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { AppComponent } from './app.component';
import { Page, ShortLink } from './url.service';

describe('AppComponent paging', () => {
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

  /** Answers the pending GET /api/urls, asserting which page and size were asked for. */
  function flushList(expected: { page: number; size: number }, body: Partial<Page<ShortLink>> = {}): void {
    const request = http.expectOne((candidate) => candidate.url === '/api/urls' && candidate.method === 'GET');
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

  const fullPage = (size: number) => Array.from({ length: size }, (_, index) => linkAt(index));

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    fixture = TestBed.createComponent(AppComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('requests the first page on load and reports the row range', () => {
    flushList({ page: 0, size: 10 }, { items: fullPage(10), totalItems: 25 });

    expect(component.page()).toBe(0);
    expect(component.pageCount()).toBe(3);
    expect(component.rangeStart()).toBe(1);
    expect(component.rangeEnd()).toBe(10);
    expect(component.hasPrevious()).toBe(false);
    expect(component.hasNext()).toBe(true);
  });

  it('walks forward and back through the pages', () => {
    flushList({ page: 0, size: 10 }, { items: fullPage(10), totalItems: 25 });

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
    flushList({ page: 0, size: 10 }, { items: fullPage(10), totalItems: 25 });

    component.goToPrevious();
    component.goToPage(3);
    component.goToPage(0);

    // http.verify() in afterEach fails if any of those issued a request.
    expect(component.page()).toBe(0);
  });

  it('restarts at the first page when the page size changes', () => {
    flushList({ page: 0, size: 10 }, { items: fullPage(10), totalItems: 25 });
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
    component.goToNext();
    flushList({ page: 1, size: 10 }, { items: [linkAt(10)], totalItems: 11 });
    expect(component.page()).toBe(1);

    component.remove(linkAt(10));
    http.expectOne('/api/urls/code10').flush(null);

    // The refresh lands on an empty page 1, so the component retries the last real page.
    flushList({ page: 1, size: 10 }, { items: [], totalItems: 10, totalPages: 1 });
    flushList({ page: 0, size: 10 }, { items: fullPage(10), totalItems: 10 });

    expect(component.page()).toBe(0);
    expect(component.links().length).toBe(10);
  });

  it('jumps to the first page after creating a link', () => {
    flushList({ page: 0, size: 10 }, { items: fullPage(10), totalItems: 25 });
    component.goToNext();
    flushList({ page: 1, size: 10 }, { items: fullPage(10), totalItems: 25 });

    component.form.patchValue({ url: 'https://example.com/new' });
    component.submit();
    http.expectOne((candidate) => candidate.method === 'POST').flush(linkAt(99));

    flushList({ page: 0, size: 10 }, { items: fullPage(10), totalItems: 26 });
    expect(component.page()).toBe(0);
  });
});
