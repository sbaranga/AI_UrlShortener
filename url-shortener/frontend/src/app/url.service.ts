import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';

export interface ShortLink {
  shortCode: string;
  shortUrl: string;
  longUrl: string;
  createdAt: string;
  expiresAt?: string;
  clickCount: number;
}

export interface ShortenRequest {
  url: string;
  customAlias?: string;
  expiresInDays?: number;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/** Shape of the backend's error body (see ApiError on the Java side). */
interface ApiError {
  status: number;
  error: string;
  message?: string;
  fieldErrors?: Record<string, string>;
}

@Injectable({ providedIn: 'root' })
export class UrlService {
  /** Relative path: the dev server proxies /api through to the Spring Boot app (see proxy.conf.json). */
  private readonly endpoint = '/api/urls';
  private readonly http = inject(HttpClient);

  shorten(request: ShortenRequest): Observable<ShortLink> {
    return this.http.post<ShortLink>(this.endpoint, request).pipe(catchError(toReadableError));
  }

  list(page = 0, size = 20): Observable<Page<ShortLink>> {
    return this.http
      .get<Page<ShortLink>>(this.endpoint, { params: { page, size } })
      .pipe(catchError(toReadableError));
  }

  stats(shortCode: string): Observable<ShortLink> {
    return this.http
      .get<ShortLink>(`${this.endpoint}/${encodeURIComponent(shortCode)}`)
      .pipe(catchError(toReadableError));
  }

  remove(shortCode: string): Observable<void> {
    return this.http
      .delete<void>(`${this.endpoint}/${encodeURIComponent(shortCode)}`)
      .pipe(catchError(toReadableError));
  }
}

/** Turns an HttpErrorResponse into an Error whose message is worth showing to a user. */
function toReadableError(response: HttpErrorResponse): Observable<never> {
  if (response.status === 0) {
    return throwError(() => new Error('Cannot reach the server. Is the backend running on port 8080?'));
  }
  const body = response.error as ApiError | null;
  const fieldError = body?.fieldErrors ? Object.values(body.fieldErrors)[0] : undefined;
  return throwError(() => new Error(fieldError ?? body?.message ?? `Request failed (${response.status})`));
}
