import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { interval } from 'rxjs';
import { AnalyticsSummary, ShortLink, UrlService } from './url.service';

@Component({
  selector: 'app-root',
  imports: [ReactiveFormsModule, DatePipe],
  templateUrl: './app.component.html',
  styleUrl: './app.component.css',
})
export class AppComponent {
  private readonly urls = inject(UrlService);
  private readonly formBuilder = inject(NonNullableFormBuilder);

  /** Mirrors the backend's alias rule so the user gets told before a round trip. */
  private static readonly ALIAS_PATTERN = /^[A-Za-z0-9_-]{3,16}$/;

  /** All within the backend's 1..100 page-size bounds, so the server never clamps our choice. */
  readonly pageSizeOptions = [10, 20, 50, 100] as const;

  /**
   * How often the dashboard re-reads the analytics while live updates are on. Each tick costs two
   * requests, so this is kept well inside the backend's per-IP rate limit.
   */
  static readonly POLL_INTERVAL_MS = 10_000;

  readonly form = this.formBuilder.group({
    url: ['', [Validators.required, Validators.pattern(/^https?:\/\/\S+$/i)]],
    customAlias: ['', [Validators.pattern(AppComponent.ALIAS_PATTERN)]],
    expiresInDays: [null as number | null, [Validators.min(1), Validators.max(3650)]],
  });

  readonly links = signal<ShortLink[]>([]);
  readonly created = signal<ShortLink | null>(null);
  readonly error = signal<string | null>(null);
  readonly submitting = signal(false);
  readonly copiedCode = signal<string | null>(null);

  readonly page = signal(0);
  readonly pageSize = signal<number>(this.pageSizeOptions[0]);
  readonly totalItems = signal(0);
  readonly loading = signal(false);

  readonly summary = signal<AnalyticsSummary | null>(null);
  readonly liveUpdates = signal(true);
  readonly lastUpdated = signal<Date | null>(null);

  /** At least one page, so the UI reads "Page 1 of 1" rather than "of 0" when there is nothing yet. */
  readonly pageCount = computed(() => Math.max(1, Math.ceil(this.totalItems() / this.pageSize())));
  readonly hasPrevious = computed(() => this.page() > 0);
  readonly hasNext = computed(() => this.page() + 1 < this.pageCount());
  /** 1-based index of the first row on this page, for the "x-y of n" summary. */
  readonly rangeStart = computed(() => (this.totalItems() === 0 ? 0 : this.page() * this.pageSize() + 1));
  readonly rangeEnd = computed(() => this.page() * this.pageSize() + this.links().length);

  constructor() {
    this.refresh();
    interval(AppComponent.POLL_INTERVAL_MS)
      .pipe(takeUntilDestroyed())
      .subscribe(() => {
        // Skip the tick when paused, or when a request is already in flight, so a slow backend
        // cannot build up a queue of overlapping polls.
        if (this.liveUpdates() && !this.loading()) {
          this.reload({ silent: true });
        }
      });
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const username = window.prompt('Username required to create this URL:');
    if (username === null) {
      return;
    }
    const password = window.prompt('Password required to create this URL:');
    if (password === null) {
      return;
    }
    const { url, customAlias, expiresInDays } = this.form.getRawValue();
    this.submitting.set(true);
    this.error.set(null);
    this.urls
      .shorten({
        url: url.trim(),
        // Omit the optional fields entirely when unset; the API treats them as absent, not empty.
        ...(customAlias.trim() ? { customAlias: customAlias.trim() } : {}),
        ...(expiresInDays ? { expiresInDays: Number(expiresInDays) } : {}),
      }, username, password)
      .subscribe({
        next: (link) => {
          this.created.set(link);
          this.form.reset();
          this.submitting.set(false);
          // Newest links sort first, so the one just created lives on page 1.
          this.load(0);
          this.loadSummary();
        },
        error: (err: Error) => {
          this.error.set(err.message);
          this.submitting.set(false);
        },
      });
  }

  refresh(): void {
    this.reload({ silent: false });
  }

  toggleLiveUpdates(): void {
    const enabled = !this.liveUpdates();
    this.liveUpdates.set(enabled);
    // Turning it back on should show current figures rather than waiting out the interval.
    if (enabled) {
      this.reload({ silent: true });
    }
  }

  goToPage(page: number): void {
    if (page === this.page() || page < 0 || page >= this.pageCount()) {
      return;
    }
    this.load(page);
  }

  goToFirst(): void {
    this.goToPage(0);
  }

  goToPrevious(): void {
    this.goToPage(this.page() - 1);
  }

  goToNext(): void {
    this.goToPage(this.page() + 1);
  }

  goToLast(): void {
    this.goToPage(this.pageCount() - 1);
  }

  changePageSize(event: Event): void {
    const size = Number((event.target as HTMLSelectElement).value);
    if (!Number.isFinite(size) || size === this.pageSize()) {
      return;
    }
    this.pageSize.set(size);
    // Row offsets mean nothing across a size change, so restart from the first page.
    this.load(0);
  }

  remove(link: ShortLink): void {
    const username = window.prompt('Username required to delete this URL:');
    if (username === null) {
      return;
    }
    const password = window.prompt('Password required to delete this URL:');
    if (password === null) {
      return;
    }

    this.urls.remove(link.shortCode, username, password).subscribe({
      next: () => {
        if (this.created()?.shortCode === link.shortCode) {
          this.created.set(null);
        }
        this.refresh();
      },
      error: (err: Error) => this.error.set(err.message),
    });
  }

  /** Re-reads both the current page and the totals, which is what "refresh" means to a user. */
  private reload(options: { silent: boolean }): void {
    this.load(this.page(), options);
    this.loadSummary();
  }

  private loadSummary(): void {
    this.urls.summary().subscribe({
      next: (summary) => {
        this.summary.set(summary);
        this.lastUpdated.set(new Date());
      },
      error: (err: Error) => this.error.set(err.message),
    });
  }

  /**
   * Fetches one page of links. A silent load is a background poll: it must not flip the button
   * into its loading state, or the dashboard would flicker every interval.
   */
  private load(requestedPage: number, options: { silent: boolean } = { silent: false }): void {
    if (!options.silent) {
      this.loading.set(true);
    }
    this.urls.list(requestedPage, this.pageSize()).subscribe({
      next: (result) => {
        // Deleting the last row of the last page (or another client doing so) leaves the requested
        // page past the end; fall back to the last page that still has rows.
        const lastPage = Math.max(0, result.totalPages - 1);
        if (result.items.length === 0 && result.page > lastPage) {
          this.load(lastPage, options);
          return;
        }
        this.links.set(result.items);
        this.page.set(result.page);
        // Trust the server's echo of size in case it ever clamps what we asked for.
        this.pageSize.set(result.size);
        this.totalItems.set(result.totalItems);
        this.loading.set(false);
      },
      error: (err: Error) => {
        this.error.set(err.message);
        this.loading.set(false);
      },
    });
  }

  async copy(link: ShortLink): Promise<void> {
    try {
      await navigator.clipboard.writeText(link.shortUrl);
      this.copiedCode.set(link.shortCode);
      setTimeout(() => this.copiedCode.update((code) => (code === link.shortCode ? null : code)), 1500);
    } catch {
      // Clipboard access needs a secure context and user permission; surface it rather than failing silently.
      this.error.set('Could not copy to the clipboard. Copy the link manually.');
    }
  }

  dismissError(): void {
    this.error.set(null);
  }

  /** True once the control has been touched and is invalid, so errors are not shown on a pristine form. */
  showError(controlName: 'url' | 'customAlias' | 'expiresInDays'): boolean {
    const control = this.form.controls[controlName];
    return control.invalid && (control.touched || control.dirty);
  }
}
