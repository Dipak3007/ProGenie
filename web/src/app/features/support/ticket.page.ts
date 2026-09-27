import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { DatePipe } from '@angular/common';
import { httpResource } from '@angular/common/http';
import { RouterLink } from '@angular/router';
import { LucideArrowLeft as ArrowLeft, LucideDynamicIcon } from '@lucide/angular';

import { API } from '../../core/api/api';
import { TicketDetail } from '../../core/api/models';
import { AuthStore } from '../../core/auth/auth-store';
import { EmptyState, Skeleton } from '../../shared/ui/kit';
import { TicketThread } from './ticket-thread';

/** One report, for the customer or Genie involved. */
@Component({
  selector: 'pg-ticket-page',
  imports: [DatePipe, RouterLink, LucideDynamicIcon, EmptyState, Skeleton, TicketThread],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a [routerLink]="base()" class="inline-flex items-center gap-1 text-sm font-semibold text-muted hover:text-brand">
      <svg [lucideIcon]="ArrowLeft" [size]="16" aria-hidden="true"></svg> All reports
    </a>
    @if (ticket.value(); as t) {
      <div class="mt-3 flex flex-col gap-1">
        <h1 class="text-2xl font-bold sm:text-3xl">{{ t.subject }}</h1>
        <p class="text-sm text-muted">
          @if (t.bookingId) {
            About <a [routerLink]="[bookingBase(), t.bookingId]" class="font-semibold text-brand hover:underline">{{ t.bookingRef }}</a>
            · {{ t.serviceName }} ·
          }
          @if (t.status === 'OPEN' || t.status === 'IN_REVIEW' || t.status === 'AWAITING_REPLY') {
            we aim to resolve it by {{ t.resolutionDue | date: 'EEE d MMM, h:mm a' }}
          } @else {
            {{ t.ticketRef }}
          }
        </p>
      </div>
      <pg-ticket-thread class="mt-6 block max-w-3xl" [ticket]="t" (changed)="ticket.set($event)" />
    } @else if (ticket.isLoading()) {
      <pg-skeleton class="mt-6 block" [rows]="3" />
    } @else {
      <pg-empty class="mt-6 block" title="Report not found" message="It may belong to another account.">
        <a [routerLink]="base()" class="btn-ghost">All reports</a>
      </pg-empty>
    }
  `,
})
export default class TicketPage {
  readonly id = input.required<string>();
  private readonly auth = inject(AuthStore);
  protected readonly ticket = httpResource<TicketDetail>(() => `${API}/tickets/${this.id()}`);
  protected readonly base = computed(() => (this.auth.role() === 'GENIE' ? '/genie/tickets' : '/account/tickets'));
  protected readonly bookingBase = computed(() => (this.auth.role() === 'GENIE' ? '/genie/bookings' : '/account/bookings'));
  protected readonly ArrowLeft = ArrowLeft;
}
