import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { LucideChevronRight as ChevronRight, LucideDynamicIcon, LucideLifeBuoy as LifeBuoy } from '@lucide/angular';

import { API, query } from '../../core/api/api';
import { Page, TicketSummary } from '../../core/api/models';
import { AuthStore } from '../../core/auth/auth-store';
import { TICKET_CATEGORY_LABEL } from '../../shared/format';
import { EmptyState, PageHead, Pager, Skeleton, TabItem, Tabs } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';

/** Reported problems and privacy requests for the logged-in customer or Genie. */
@Component({
  selector: 'pg-tickets-page',
  imports: [DatePipe, RouterLink, LucideDynamicIcon, EmptyState, PageHead, Pager, Skeleton, StatusBadge, Tabs],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Reported problems" subtitle="Problems with a booking and privacy requests, with our replies." />
    <pg-tabs class="mt-6" label="Filter reports" [items]="tabs" [value]="status()" (valueChange)="setStatus($event)" />

    @if (list.value(); as p) {
      @if (p.items.length) {
        <ul class="mt-4 space-y-3">
          @for (t of p.items; track t.id) {
            <li>
              <a [routerLink]="[base(), t.id]" class="card flex items-center gap-4 p-4 transition hover:border-brand-soft sm:p-5">
                <div class="min-w-0 flex-1">
                  <div class="flex flex-wrap items-center gap-2">
                    <pg-status [status]="t.status" [label]="t.status === 'REJECTED' ? 'Closed without action' : null" />
                    <span class="text-xs text-muted">{{ t.ticketRef }}{{ t.bookingRef ? ' · ' + t.bookingRef : '' }}</span>
                  </div>
                  <p class="mt-1.5 truncate font-semibold">{{ t.subject }}</p>
                  <p class="text-sm text-muted">{{ categoryLabel[t.category] }} · updated {{ t.updatedAt | date: 'd MMM, h:mm a' }}</p>
                </div>
                <svg [lucideIcon]="ChevronRight" [size]="20" class="shrink-0 text-muted" aria-hidden="true"></svg>
              </a>
            </li>
          }
        </ul>
        <pg-pager [page]="page()" [size]="20" [total]="p.total" (pageChange)="page.set($event)" />
      } @else {
        <pg-empty class="mt-4 block" [icon]="LifeBuoy" title="Nothing here" message="To report a problem, open the booking and tap “Report a problem”." />
      }
    } @else if (list.isLoading()) {
      <pg-skeleton class="mt-4 block" [rows]="3" />
    }
  `,
})
export default class TicketsPage {
  private readonly auth = inject(AuthStore);
  protected readonly categoryLabel = TICKET_CATEGORY_LABEL;
  protected readonly tabs: TabItem[] = [
    { value: 'ACTIVE', label: 'Open' },
    { value: '', label: 'All' },
  ];
  protected readonly status = signal('ACTIVE');
  protected readonly page = signal(0);
  protected readonly base = computed(() => (this.auth.role() === 'GENIE' ? '/genie/tickets' : '/account/tickets'));
  protected readonly list = httpResource<Page<TicketSummary>>(() => `${API}/tickets${query({ status: this.status(), page: this.page(), size: 20 })}`);

  protected setStatus(value: string): void {
    this.status.set(value);
    this.page.set(0);
  }

  protected readonly ChevronRight = ChevronRight;
  protected readonly LifeBuoy = LifeBuoy;
}
