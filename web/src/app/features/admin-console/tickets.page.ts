import { ChangeDetectionStrategy, Component, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { LucideAlarmClock as AlarmClock, LucideDynamicIcon, LucideLifeBuoy as LifeBuoy, LucideSearch as SearchIcon } from '@lucide/angular';

import { API, query } from '../../core/api/api';
import { Page, TicketSummary } from '../../core/api/models';
import { TICKET_CATEGORY_LABEL } from '../../shared/format';
import { EmptyState, PageHead, Pager, Skeleton, TabItem, Tabs } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';

/** Complaints and privacy requests, overdue first, then by priority and deadline. */
@Component({
  selector: 'pg-admin-tickets-page',
  imports: [DatePipe, FormsModule, RouterLink, LucideDynamicIcon, EmptyState, PageHead, Pager, Skeleton, StatusBadge, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Complaints" subtitle="Reported problems and privacy requests. Overdue ones come first." />

    <div class="mt-5 flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
      <pg-tabs label="Filter complaints" [items]="tabs" [value]="tab()" (valueChange)="setTab($event)" />
      <form class="flex gap-2" (ngSubmit)="search()">
        <div class="relative flex-1 lg:w-72">
          <svg [lucideIcon]="SearchIcon" [size]="16" class="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" aria-hidden="true"></svg>
          <input name="q" type="search" class="field py-2.5 pl-10" placeholder="PGT-…, PG-…, subject or name" [(ngModel)]="q" aria-label="Search complaints" />
        </div>
        <select name="priority" class="field w-auto py-2.5" [(ngModel)]="priority" (ngModelChange)="search()" aria-label="Priority">
          <option value="">Any priority</option>
          <option value="URGENT">Urgent</option>
          <option value="HIGH">High</option>
          <option value="NORMAL">Normal</option>
        </select>
      </form>
    </div>

    <div class="mt-5">
      @if (list.value(); as p) {
        <ul class="space-y-3">
          @for (t of p.items; track t.id) {
            <li>
              <a
                [routerLink]="['/admin/tickets', t.id]"
                class="card flex flex-col gap-3 p-4 transition hover:border-brand-soft sm:flex-row sm:items-center sm:p-5"
                [class.border-rose-300]="t.overdue"
              >
                <div class="min-w-0 flex-1">
                  <div class="flex flex-wrap items-center gap-2">
                    <pg-status [status]="t.status" [label]="t.status === 'AWAITING_REPLY' ? 'Awaiting reply' : t.status === 'REJECTED' ? 'Rejected' : null" />
                    @if (t.priority !== 'NORMAL') {
                      <pg-status [status]="t.priority" />
                    }
                    @if (t.overdue) {
                      <span class="inline-flex items-center gap-1 rounded-full bg-rose-600 px-2.5 py-0.5 text-xs font-semibold text-white">
                        <svg [lucideIcon]="AlarmClock" [size]="12" aria-hidden="true"></svg> Overdue
                      </span>
                    }
                    <span class="text-xs text-muted">{{ t.ticketRef }}{{ t.bookingRef ? ' · ' + t.bookingRef : '' }}</span>
                  </div>
                  <p class="mt-1.5 truncate font-semibold">{{ t.subject }}</p>
                  <p class="text-sm text-muted">
                    {{ categoryLabel[t.category] }} · {{ t.raisedByName }} ({{ t.raisedByRole === 'GENIE' ? 'Genie' : 'customer' }})
                    {{ t.assignedToName ? ' · with ' + t.assignedToName : ' · unassigned' }}
                  </p>
                </div>
                <div class="shrink-0 text-sm sm:text-right">
                  @if (t.status === 'OPEN' || t.status === 'IN_REVIEW' || t.status === 'AWAITING_REPLY') {
                    <p [class]="t.overdue ? 'font-semibold text-rose-700' : 'text-muted'">Resolve by {{ t.resolutionDue | date: 'd MMM, h:mm a' }}</p>
                  } @else {
                    <p class="text-muted">Updated {{ t.updatedAt | date: 'd MMM, h:mm a' }}</p>
                  }
                </div>
              </a>
            </li>
          } @empty {
            <pg-empty [icon]="LifeBuoy" title="Nothing here" message="No complaints match this filter." />
          }
        </ul>
        <pg-pager [page]="p.page" [size]="p.size" [total]="p.total" (pageChange)="page.set($event)" />
      } @else if (list.isLoading()) {
        <pg-skeleton [rows]="4" />
      }
    </div>
  `,
})
export default class AdminTicketsPage {
  protected readonly categoryLabel = TICKET_CATEGORY_LABEL;
  protected readonly tabs: TabItem[] = [
    { value: 'ACTIVE', label: 'Open' },
    { value: 'OVERDUE', label: 'Overdue' },
    { value: 'MINE', label: 'Mine' },
    { value: 'AWAITING_REPLY', label: 'Awaiting reply' },
    { value: 'RESOLVED', label: 'Resolved' },
    { value: 'ALL', label: 'All' },
  ];
  protected readonly tab = signal('ACTIVE');
  protected readonly page = signal(0);
  protected q = '';
  protected priority = '';
  private readonly filters = signal({ q: '', priority: '' });

  protected readonly list = httpResource<Page<TicketSummary>>(() => {
    const t = this.tab();
    const f = this.filters();
    return `${API}/admin/tickets${query({
      status: t === 'OVERDUE' || t === 'MINE' ? 'ACTIVE' : t,
      overdue: t === 'OVERDUE' ? true : null,
      mine: t === 'MINE' ? true : null,
      priority: f.priority || null,
      q: f.q || null,
      page: this.page(),
      size: 20,
    })}`;
  });

  protected setTab(value: string): void {
    this.tab.set(value);
    this.page.set(0);
  }

  protected search(): void {
    this.filters.set({ q: this.q.trim(), priority: this.priority });
    this.page.set(0);
  }

  protected readonly AlarmClock = AlarmClock;
  protected readonly LifeBuoy = LifeBuoy;
  protected readonly SearchIcon = SearchIcon;
}
