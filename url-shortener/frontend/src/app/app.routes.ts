import { Routes } from '@angular/router';
import { AppComponent } from './app.component';
import { GovernanceComponent } from './governance/governance.component';

/**
 * The shortener stays on the root path; the orchestration control room is a sibling screen rather
 * than a replacement for it.
 */
export const routes: Routes = [
  { path: '', component: AppComponent, title: 'URL Shortener' },
  { path: 'governance', component: GovernanceComponent, title: 'Delivery Governance' },
  { path: '**', redirectTo: '' },
];
