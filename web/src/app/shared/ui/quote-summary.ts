import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { CurrencyPipe, DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';

import { Quote } from '../../core/api/models';
import { durationText } from '../service-lines';
import { StatusBadge } from './status-badge';

const STATUS: Record<string, { label: string; tone: 'info' | 'success' | 'neutral' | 'warning' }> = {
  SENT: { label: 'Waiting for an answer', tone: 'info' },
  ACCEPTED: { label: 'Accepted', tone: 'success' },
  DECLINED: { label: 'Declined', tone: 'neutral' },
  EXPIRED: { label: 'Expired', tone: 'warning' },
};

/** A Genie's quote after an inspection visit (Phase 3.5D), as the customer, the Genie and admins see it. */
@Component({
  selector: 'pg-quote-summary',
  imports: [CurrencyPipe, DatePipe, RouterLink, StatusBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let q = quote();
    <div class="flex flex-wrap items-center gap-2">
      <pg-status [status]="q.status" [label]="status().label" [tone]="status().tone" />
      <span class="text-xs text-muted">Version {{ q.version }} · updated {{ q.updatedAt | date: 'd MMM, h:mm a' }}</span>
    </div>
    <dl class="mt-4 space-y-2 text-sm [&_dd]:shrink-0 [&_dd]:whitespace-nowrap" data-testid="quote-lines">
      @for (l of q.lines; track l.id) {
        <div class="flex justify-between gap-3">
          <dt class="min-w-0">
            {{ l.description }}{{ l.quantity > 1 ? ' ×' + l.quantity : '' }}
            @if (l.serviceId === null) {
              <span class="ml-1 text-xs text-muted">(custom)</span>
            }
          </dt>
          <dd>{{ l.amount | currency: 'INR' }}</dd>
        </div>
      }
      <div class="flex justify-between gap-3 border-t border-line pt-2 font-semibold"><dt>Quoted price</dt><dd>{{ q.totalAmount | currency: 'INR' }}</dd></div>
      @if (q.inspectionCredit > 0) {
        <div class="flex justify-between gap-3 text-emerald-700"><dt>Inspection fee already paid</dt><dd>− {{ q.inspectionCredit | currency: 'INR' }}</dd></div>
      }
      <div class="flex justify-between gap-3 text-base font-bold" data-testid="quote-job-amount">
        <dt>{{ audience() === 'CUSTOMER' ? 'You pay for the job' : 'Job price' }}</dt><dd>{{ q.jobAmount | currency: 'INR' }}</dd>
      </div>
    </dl>
    <p class="mt-2 text-xs text-muted">
      Plus the travel fee to your address. The job takes about {{ duration() }}.
      @if (q.status === 'SENT') {
        Valid until {{ q.validUntil | date: 'd MMM, h:mm a' }}.
      }
    </p>
    @if (q.note) {
      <p class="mt-3 rounded-xl bg-surface px-3 py-2 text-sm"><span class="font-semibold">Note:</span> {{ q.note }}</p>
    }
    @if (q.status === 'DECLINED' && q.declineReason) {
      <p class="mt-3 text-sm text-muted">Declined: “{{ q.declineReason }}”</p>
    }
    @if (q.jobBookingId) {
      <p class="mt-3 text-sm">
        Booked as
        <a [routerLink]="[jobLinkBase(), q.jobBookingId]" class="font-semibold text-brand underline">{{ q.jobBookingRef }}</a>
      </p>
    }
  `,
})
export class QuoteSummary {
  readonly quote = input.required<Quote>();
  readonly audience = input<'CUSTOMER' | 'GENIE' | 'ADMIN'>('CUSTOMER');
  protected readonly status = computed(() => STATUS[this.quote().status] ?? { label: this.quote().status, tone: 'neutral' as const });
  protected readonly duration = computed(() => durationText(this.quote().durationMinutes));
  protected readonly jobLinkBase = computed(() =>
    this.audience() === 'GENIE' ? '/genie/bookings' : this.audience() === 'ADMIN' ? '/admin/bookings' : '/account/bookings',
  );
}
