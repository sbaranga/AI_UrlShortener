import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

/** Bootstrap root: renders the site nav and hosts whichever screen the route selects. */
@Component({
  selector: 'app-shell',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  template: `
    <nav class="nav">
      <div class="nav__inner">
        <a class="nav__link" routerLink="/" routerLinkActive="nav__link--active" [routerLinkActiveOptions]="{ exact: true }">
          Shortener
        </a>
        <a class="nav__link" routerLink="/governance" routerLinkActive="nav__link--active">Governance</a>
      </div>
    </nav>
    <router-outlet />
  `,
  styles: `
    .nav {
      border-bottom: 1px solid var(--color-border);
      background: var(--color-surface);
    }

    .nav__inner {
      display: flex;
      gap: 0.25rem;
      max-width: 68rem;
      margin: 0 auto;
      padding: 0 1.25rem;
    }

    .nav__link {
      color: var(--color-muted);
      text-decoration: none;
      font-size: 0.875rem;
      font-weight: 600;
      padding: 0.75rem 0.625rem;
      border-bottom: 2px solid transparent;
    }

    .nav__link:hover {
      color: var(--color-text);
    }

    .nav__link--active {
      color: var(--color-accent);
      border-bottom-color: var(--color-accent);
    }
  `,
})
export class ShellComponent {}
