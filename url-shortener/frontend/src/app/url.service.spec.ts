import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { ShortLink, UrlService } from './url.service';

describe('UrlService', () => {
  let service: UrlService;
  let http: HttpTestingController;

  const link: ShortLink = {
    shortCode: 'abc1234',
    shortUrl: 'http://localhost:8080/abc1234',
    longUrl: 'https://example.com',
    createdAt: '2026-01-01T00:00:00Z',
    clickCount: 0,
  };

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(UrlService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('posts the request body to /api/urls', () => {
    let result: ShortLink | undefined;
    service.shorten({ url: 'https://example.com' }).subscribe((value) => (result = value));

    const request = http.expectOne('/api/urls');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ url: 'https://example.com' });
    request.flush(link);

    expect(result).toEqual(link);
  });

  it('sends paging parameters when listing', () => {
    service.list(2, 5).subscribe();

    const request = http.expectOne((candidate) => candidate.url === '/api/urls');
    expect(request.request.params.get('page')).toBe('2');
    expect(request.request.params.get('size')).toBe('5');
    request.flush({ items: [], page: 2, size: 5, totalItems: 0, totalPages: 0 });
  });

  it('escapes the code when deleting', () => {
    service.remove('a/b').subscribe();

    http.expectOne('/api/urls/a%2Fb').flush(null);
  });

  it('surfaces the API error message', () => {
    let message: string | undefined;
    service.shorten({ url: 'nope' }).subscribe({ error: (error: Error) => (message = error.message) });

    http
      .expectOne('/api/urls')
      .flush({ status: 400, error: 'Bad Request', message: 'url must be absolute' }, { status: 400, statusText: 'Bad Request' });

    expect(message).toBe('url must be absolute');
  });

  it('prefers a field error over the generic message', () => {
    let message: string | undefined;
    service.shorten({ url: '' }).subscribe({ error: (error: Error) => (message = error.message) });

    http.expectOne('/api/urls').flush(
      {
        status: 400,
        error: 'Bad Request',
        message: 'Request validation failed',
        fieldErrors: { url: 'url is required' },
      },
      { status: 400, statusText: 'Bad Request' },
    );

    expect(message).toBe('url is required');
  });

  it('explains a connection failure', () => {
    let message: string | undefined;
    service.list().subscribe({ error: (error: Error) => (message = error.message) });

    http
      .expectOne((candidate) => candidate.url === '/api/urls')
      .error(new ProgressEvent('error'), { status: 0, statusText: 'Unknown Error' });

    expect(message).toContain('Cannot reach the server');
  });
});
