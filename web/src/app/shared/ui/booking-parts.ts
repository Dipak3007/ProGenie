import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { LucideChevronRight as ChevronRight, LucideDynamicIcon, LucideMapPin as MapPin } from '@lucide/angular';

import { BookingDetail, BookingSummary, HistoryItem } from '../../core/api/models';
import { humanize, statusMeta } from '../format';
import { categoryIcon, iconNameForSlug } from './icons';
import { StatusBadge } from './status-badge';

/** One booking in a list. Card on phones, compact row on wider screens. */
@Component({
  selector: 'pg-booking-row',
  imports: [RouterLink, CurrencyPipe, DatePipe, LucideDynamicIcon, StatusBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let b = booking();
    <a
      [routerLink]="[linkBase(), b.id]"
      class="card group flex items-start gap-3 p-4 transition hover:border-brand-soft hover:shadow-lg hover:shadow-brand/5 sm:items-center sm:gap-4"
    >
      <span class="grid size-11 shrink-0 place-items-center rounded-xl bg-brand-mist text-brand-600 sm:size-12">
        <svg [lucideIcon]="icon()" [size]="22" aria-hidden="true"></svg>
      </span>
      <div class="min-w-0 flex-1">
        <div class="flex flex-wrap items-center gap-x-2 gap-y-1">
          <p class="truncate font-semibold">{{ b.serviceName }}</p>
          <pg-status [status]="b.status" [label]="statusLabel()" />
          @if (b.cancellationFeeStatus === 'DUE') {
            <pg-status status="DUE" />
          } @else if (b.status === 'COMPLETED' && b.paymentStatus === 'UNPAID') {
            <pg-status status="UNPAID" label="Payment due" />
          }
        </div>
        <p class="mt-1 text-sm text-muted">
          {{ b.slotStart | date: 'EEE d MMM · h:mm a' }} · {{ counterpartLabel() }} {{ b.counterpartName }}
        </p>
        <p class="mt-0.5 flex items-center gap-1 text-xs text-muted">
          @if (b.area) {
            <svg [lucideIcon]="MapPin" [size]="12" aria-hidden="true"></svg>{{ b.area }} ·
          }
          {{ b.bookingRef }}
        </p>
      </div>
      <div class="flex shrink-0 items-center gap-2 self-center">
        <span class="font-display text-lg font-bold">{{ b.totalAmount | currency: 'INR' : 'symbol' : '1.0-0' }}</span>
        <svg [lucideIcon]="ChevronRight" [size]="18" class="hidden text-muted transition group-hover:translate-x-0.5 sm:block" aria-hidden="true"></svg>
      </div>
    </a>
  `,
})
export class BookingRow {
  readonly booking = input.required<BookingSummary>();
  readonly linkBase = input.required<string>();
  readonly counterpartLabel = input('with');
  /** Whose list this is: labels read differently for the Genie ("Needs your reply") than for the customer. */
  readonly audience = input<'CUSTOMER' | 'GENIE' | 'ADMIN'>('CUSTOMER');
  protected readonly statusLabel = computed(() => {
    const status = this.booking().status;
    if (status === 'REQUESTED') return this.audience() === 'GENIE' ? 'Needs your reply' : this.audience() === 'ADMIN' ? 'Requested' : null;
    return null;
  });

  protected readonly icon = computed(() => categoryIcon(iconNameForSlug(this.booking().categorySlug)));
  protected readonly MapPin = MapPin;
  protected readonly ChevronRight = ChevronRight;
}

/** Price breakdown of a booking. `audience` decides whether commission/payout lines are shown. */
@Component({
  selector: 'pg-price-summary',
  imports: [CurrencyPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let b = booking();
    <dl class="space-y-2 text-sm [&_dd]:shrink-0 [&_dd]:whitespace-nowrap">
      <div class="flex justify-between gap-3"><dt class="text-muted">{{ b.serviceName }}</dt><dd>{{ b.serviceAmount | currency: 'INR' }}</dd></div>
      <div class="flex justify-between gap-3">
        <dt class="text-muted">Travel ({{ b.distanceKm }} km)</dt>
        <dd>{{ b.travelFee > 0 ? (b.travelFee | currency: 'INR') : 'Free' }}</dd>
      </div>
      @if (b.extraAmount > 0) {
        <div class="flex justify-between gap-3">
          <dt class="text-muted">Extras{{ b.extraNote ? ' · ' + b.extraNote : '' }}</dt>
          <dd>{{ b.extraAmount | currency: 'INR' }}</dd>
        </div>
      }
      @if (b.tipAmount > 0) {
        <div class="flex justify-between gap-3"><dt class="text-muted">Tip</dt><dd>{{ b.tipAmount | currency: 'INR' }}</dd></div>
      }
      <div class="flex justify-between gap-3 border-t border-line pt-2 text-base font-bold">
        <dt>Total</dt><dd>{{ b.totalAmount | currency: 'INR' }}</dd>
      </div>
      @if (audience() !== 'CUSTOMER') {
        <div class="flex justify-between gap-3 text-muted"><dt>ProGenie commission ({{ rate() }}% of service)</dt><dd class="whitespace-nowrap">− {{ b.commissionAmount | currency: 'INR' }}</dd></div>
        <div class="flex justify-between gap-3 font-semibold text-emerald-700"><dt>Genie earns</dt><dd>{{ b.geniePayout | currency: 'INR' }}</dd></div>
      }
      @if (b.cancellationFee && b.cancellationFee > 0) {
        <div class="flex justify-between gap-3 border-t border-line pt-2">
          <dt class="text-muted">Late-cancellation fee ({{ feeLabel() }})</dt><dd>{{ b.cancellationFee | currency: 'INR' }}</dd>
        </div>
      }
    </dl>
  `,
})
export class PriceSummary {
  readonly booking = input.required<BookingDetail>();
  readonly audience = input<'CUSTOMER' | 'GENIE' | 'ADMIN'>('CUSTOMER');
  protected readonly feeLabel = computed(() => statusMeta(this.booking().cancellationFeeStatus).label.toLowerCase());
  protected readonly rate = computed(() => {
    const b = this.booking();
    return b.serviceAmount > 0 ? Math.round((b.commissionAmount / b.serviceAmount) * 1000) / 10 : 5;
  });
}

/** Vertical status history, newest last. */
@Component({
  selector: 'pg-timeline',
  imports: [DatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <ol class="relative space-y-4 border-l-2 border-line pl-5">
      @for (h of items(); track $index; let last = $last) {
        <li class="relative">
          <span
            class="absolute -left-[1.72rem] top-1 size-3 rounded-full ring-4 ring-white"
            [class]="last ? 'bg-brand' : 'bg-brand-soft'"
            aria-hidden="true"
          ></span>
          <p class="text-sm font-semibold">{{ label(h) }}</p>
          <p class="text-xs text-muted">
            {{ h.changedAt | date: 'd MMM, h:mm a' }}{{ h.actorRole ? ' · by ' + actor(h.actorRole) : '' }}
          </p>
          @if (h.reason) {
            <p class="mt-1 text-sm text-ink/75">{{ reasonText(h.reason) }}</p>
          }
        </li>
      }
    </ol>
  `,
})
export class Timeline {
  readonly items = input.required<HistoryItem[]>();

  protected label(h: HistoryItem): string {
    if (!h.fromStatus) return 'Booking requested';
    if (h.reason?.startsWith('Moved to ')) return 'Rescheduled · waiting for the Genie to confirm';
    if (h.fromStatus === h.toStatus) return h.toStatus === 'REQUESTED' ? 'Rescheduled' : humanize(h.toStatus);
    return statusMeta(h.toStatus).label;
  }

  /** "Moved to 2026-10-01T10:30" → "Moved to Thu 1 Oct, 10:30"; other reasons are quoted. */
  protected reasonText(reason: string): string {
    const m = /^Moved to (\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/.exec(reason);
    if (m) {
      const day = new Date(`${m[1]}T00:00:00Z`).toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' });
      return `Moved to ${day}, ${m[2]}`;
    }
    return `“${reason}”`;
  }

  protected actor(role: string): string {
    return { CUSTOMER: 'customer', GENIE: 'Genie', ADMIN: 'ProGenie team', SYSTEM: 'system' }[role] ?? role.toLowerCase();
  }
}
