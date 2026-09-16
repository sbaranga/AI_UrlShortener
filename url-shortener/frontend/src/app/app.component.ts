import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { ShortLink, UrlService } from './url.service';

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

  /** At least one page, so the UI reads "Page 1 of 1" rather than "of 0" when there is nothing yet. */
  readonly pageCount = computed(() => Math.max(1, Math.ceil(this.totalItems() / this.pageSize())));
  readonly hasPrevious = computed(() => this.page() > 0);
  readonly hasNext = computed(() => this.page() + 1 < this.pageCount());
  /** 1-based index of the first row on this page, for the "x-y of n" summary. */
  readonly rangeStart = computed(() => (this.totalItems() === 0 ? 0 : this.page() * this.pageSize() + 1));
  readonly rangeEnd = computed(() => this.page() * this.pageSize() + this.links().length);

  constructor() {
    this.refresh();
  }

  submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
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
      })
      .subscribe({
        next: (link) => {
          this.created.set(link);
          this.form.reset();
          this.submitting.set(false);
          // Newest links sort first, so the one just created lives on page 1.
          this.load(0);
        },
        error: (err: Error) => {
          this.error.set(err.message);
          this.submitting.set(false);
        },
      });
  }

  refresh(): void {
    this.load(this.page());
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
    this.urls.remove(link.shortCode).subscribe({
      next: () => {
        if (this.created()?.shortCode === link.shortCode) {
          this.created.set(null);
        }
        this.refresh();
      },
      error: (err: Error) => this.error.set(err.message),
    });
  }

  private load(requestedPage: number): void {
    this.loading.set(true);
    this.urls.list(requestedPage, this.pageSize()).subscribe({
      next: (result) => {
        // Deleting the last row of the last page (or another client doing so) leaves the requested
        // page past the end; fall back to the last page that still has rows.
        const lastPage = Math.max(0, result.totalPages - 1);
        if (result.items.length === 0 && result.page > lastPage) {
          this.load(lastPage);
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
