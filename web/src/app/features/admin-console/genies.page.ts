import { ChangeDetectionStrategy, Component, input, linkedSignal, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import {
  LucideChevronRight as ChevronRight,
  LucideDynamicIcon,
  LucideFileText as FileText,
  LucideFlag as Flag,
  LucideSearch as SearchIcon,
  LucideShieldCheck as ShieldCheck,
} from '@lucide/angular';

import { API, query } from '../../core/api/api';
import { Page, QueueItem } from '../../core/api/models';
import { Avatar } from '../../shared/ui/avatar';
import { EmptyState, PageHead, Pager, Skeleton, TabItem, Tabs } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';

@Component({
  selector: 'pg-admin-genies-page',
  imports: [RouterLink, DatePipe, LucideDynamicIcon, Avatar, EmptyState, PageHead, Pager, Skeleton, StatusBadge, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Genies" subtitle="Verify new Genies, follow up on flags and manage suspensions." />

    <div class="mt-5 flex flex-col gap-3 lg:flex-row lg:items-center">
      <pg-tabs class="min-w-0 flex-1" [items]="tabs" [value]="statusTab()" label="Verification status" (valueChange)="setStatus($event)" />
      <div class="flex gap-2">
        <label class="flex min-h-11 items-center gap-2 rounded-full border border-line bg-white px-4 text-sm font-semibold">
          <input type="checkbox" class="size-4 accent-rose-600" [checked]="flaggedOnly()" (change)="flaggedOnly.set($any($event.target).checked); page$.set(0)" />
          <svg [lucideIcon]="Flag" [size]="14" class="text-rose-600" aria-hidden="true"></svg> Flagged
        </label>
        <form class="relative flex-1 lg:w-64 lg:flex-none" role="search" (submit)="$event.preventDefault(); q.set(term); page$.set(0)">
          <label for="g-q" class="sr-only">Search Genies</label>
          <svg [lucideIcon]="SearchIcon" [size]="16" class="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" aria-hidden="true"></svg>
          <input id="g-q" class="field rounded-full py-2.5 pl-10" placeholder="Name or phone" [value]="term" (input)="term = $any($event.target).value" (search)="q.set(term)" type="search" />
        </form>
      </div>
    </div>

    <div class="mt-5">
      @if (list.value(); as page) {
        <ul class="space-y-3">
          @for (g of page.items; track g.id) {
            <li>
              <a [routerLink]="['/admin/genies', g.id]" class="card group flex items-center gap-4 p-4 transition hover:border-brand-soft">
                <pg-avatar [name]="g.fullName" [size]="44" />
                <div class="min-w-0 flex-1">
                  <p class="flex flex-wrap items-center gap-2 font-semibold">
                    {{ g.fullName }} <pg-status [status]="g.verificationStatus" />
                    @if (g.flaggedAt) {
                      <pg-status status="FLAGGED" label="Flagged" tone="danger" />
                    }
                  </p>
                  <p class="mt-0.5 truncate text-sm text-muted">
                    {{ g.phone }}{{ g.baseArea ? ' · ' + g.baseArea : '' }} · {{ g.serviceCount }} services
                    · {{ g.submittedAt ? 'submitted ' + (g.submittedAt | date: 'd MMM, h:mm a') : 'joined ' + (g.createdAt | date: 'd MMM') }}
                  </p>
                  @if (g.flagReason) {
                    <p class="mt-0.5 truncate text-xs text-rose-700">{{ g.flagReason }}</p>
                  }
                </div>
                @if (g.pendingDocuments) {
                  <span class="hidden items-center gap-1 rounded-full bg-amber-50 px-3 py-1 text-xs font-semibold text-amber-900 sm:inline-flex">
                    <svg [lucideIcon]="FileText" [size]="13" aria-hidden="true"></svg> {{ g.pendingDocuments }} to check
                  </span>
                }
                <svg [lucideIcon]="ChevronRight" [size]="18" class="shrink-0 text-muted" aria-hidden="true"></svg>
              </a>
            </li>
          } @empty {
            <pg-empty [icon]="ShieldCheck" title="Nothing here" [message]="statusTab() === 'UNDER_REVIEW' ? 'The verification queue is empty. Nice work!' : 'No Genies match these filters.'" />
          }
        </ul>
        <pg-pager [page]="page.page" [size]="page.size" [total]="page.total" (pageChange)="page$.set($event)" />
      } @else if (list.isLoading()) {
        <pg-skeleton [rows]="5" />
      }
    </div>
  `,
})
export default class AdminGeniesPage {
  /** Optional ?status= and ?flagged= (links from the overview). */
  readonly status = input<string | undefined>(undefined);
  readonly flagged = input<string | undefined>(undefined);

  protected readonly statusTab = linkedSignal(() => this.status() ?? (this.flagged() ? 'ALL' : 'UNDER_REVIEW'));
  protected readonly flaggedOnly = linkedSignal(() => this.flagged() === 'true');
  protected readonly q = signal('');
  protected term = '';
  protected readonly page$ = signal(0);

  protected readonly tabs: TabItem[] = [
    { value: 'UNDER_REVIEW', label: 'To review' },
    { value: 'NEEDS_CHANGES', label: 'Needs changes' },
    { value: 'REGISTERED', label: 'Onboarding' },
    { value: 'APPROVED', label: 'Approved' },
    { value: 'SUSPENDED', label: 'Suspended' },
    { value: 'REJECTED', label: 'Rejected' },
    { value: 'ALL', label: 'All' },
  ];

  protected readonly list = httpResource<Page<QueueItem>>(
    () =>
      `${API}/admin/genies${query({
        status: this.statusTab() === 'ALL' ? null : this.statusTab(),
        flagged: this.flaggedOnly() ? true : null,
        q: this.q() || null,
        page: this.page$(),
        size: 15,
      })}`,
  );

  protected setStatus(status: string): void {
    this.statusTab.set(status);
    this.page$.set(0);
  }

  protected readonly ChevronRight = ChevronRight;
  protected readonly FileText = FileText;
  protected readonly Flag = Flag;
  protected readonly SearchIcon = SearchIcon;
  protected readonly ShieldCheck = ShieldCheck;
}
