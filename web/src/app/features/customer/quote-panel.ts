import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { Router } from '@angular/router';

import { errorCode, errorMessage } from '../../core/api/api';
import { CustomerApi } from '../../core/api/customer-api';
import { BookingDetail, PaymentMethod, Slot } from '../../core/api/models';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { Dialog } from '../../shared/ui/dialog';
import { Spinner } from '../../shared/ui/kit';
import { QuoteSummary } from '../../shared/ui/quote-summary';
import { SlotPicker } from '../../shared/ui/slot-picker';
import { durationText } from '../../shared/service-lines';

/**
 * The customer's side of an inspection visit (Phase 3.5D): waiting for the quote, then the quote with "Accept and pick
 * a time" (books the job, already accepted by the Genie) and "Decline".
 */
@Component({
  selector: 'pg-quote-panel',
  imports: [CurrencyPipe, Dialog, QuoteSummary, SlotPicker, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @let b = booking();
    <section class="card p-5" aria-labelledby="quote-h" data-testid="quote-panel">
      <h2 id="quote-h" class="text-lg font-bold">Your quote</h2>
      @if (b.quote; as q) {
        <pg-quote-summary class="mt-3" [quote]="q" />
        @if (can('ACCEPT_QUOTE')) {
          <div class="mt-4 flex flex-col gap-2 sm:flex-row">
            <button type="button" class="btn-gold min-h-12 flex-1" (click)="acceptOpen.set(true)">Accept and pick a time</button>
            <button type="button" class="btn-ghost min-h-12" [disabled]="busy()" (click)="decline()">Decline</button>
          </div>
        }
      } @else if (b.status === 'COMPLETED') {
        <p class="mt-2 text-sm text-ink/75">
          {{ first() }} will send you a priced quote within 7 days. The {{ b.serviceAmount | currency: 'INR' : 'symbol' : '1.0-0' }} inspection fee is taken off
          the job if you accept it.
        </p>
      } @else {
        <p class="mt-2 text-sm text-ink/75">
          After the visit {{ first() }} sends you a priced quote. The inspection fee is taken off the job if you accept it.
        </p>
      }
    </section>

    @if (b.quote; as q) {
      <pg-dialog [open]="acceptOpen()" heading="Pick a time for the job" [subheading]="'About ' + hours(q.durationMinutes)" size="lg" (closed)="acceptOpen.set(false)">
        @if (acceptOpen()) {
          <pg-slot-picker [genieId]="b.genie.id" [quoteId]="q.id" [selected]="slot()?.start ?? null" (picked)="slot.set($event)" />
          <fieldset class="mt-5">
            <legend class="label">How will you pay?</legend>
            <div class="flex flex-wrap gap-2">
              @for (m of methods; track m.value) {
                <label class="flex cursor-pointer items-center gap-2 rounded-full border px-4 py-2 text-sm" [class]="method() === m.value ? 'border-brand bg-brand-mist/50' : 'border-line'">
                  <input type="radio" name="quote-method" class="accent-brand" [checked]="method() === m.value" (change)="method.set(m.value)" />
                  {{ m.label }}
                </label>
              }
            </div>
          </fieldset>
          <p class="mt-4 text-sm">You pay {{ q.jobAmount | currency: 'INR' }} for the job plus the travel fee, after it is done.</p>
          @if (error()) {
            <p class="mt-3 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ error() }}</p>
          }
          <div class="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <button type="button" class="btn-ghost" (click)="acceptOpen.set(false)">Not now</button>
            <button type="button" class="btn-gold" [disabled]="!slot() || busy()" (click)="accept(q.id)">
              @if (busy()) {
                <pg-spinner />
              }
              Book the job
            </button>
          </div>
        }
      </pg-dialog>
    }
  `,
})
export class QuotePanel {
  readonly booking = input.required<BookingDetail>();
  readonly changed = output<BookingDetail>();

  private readonly api = inject(CustomerApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  protected readonly acceptOpen = signal(false);
  protected readonly slot = signal<Slot | null>(null);
  protected readonly method = signal<PaymentMethod>('CASH');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly methods = [
    { value: 'CASH' as const, label: 'Cash after the job' },
    { value: 'ONLINE' as const, label: 'Online after the job' },
  ];

  protected can(action: string): boolean {
    return this.booking().allowedActions.includes(action as never);
  }

  protected first(): string {
    return this.booking().genie.name.split(' ')[0];
  }

  protected hours(minutes: number): string {
    return durationText(minutes);
  }

  protected accept(quoteId: string): void {
    const slot = this.slot();
    if (!slot || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.api.acceptQuote(quoteId, { slotStart: slot.start, paymentMethod: this.method(), tipAmount: 0, notes: null }).subscribe({
      next: (job) => {
        this.busy.set(false);
        this.acceptOpen.set(false);
        this.toast.success(`Job booked for ${new Date(job.slotStart).toLocaleString('en-IN', { weekday: 'short', day: 'numeric', month: 'short', hour: 'numeric', minute: '2-digit' })}.`);
        void this.router.navigate(['/account/bookings', job.id]);
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err, 'Could not book the job.'));
        if (errorCode(err) === 'SLOT_TAKEN' || errorCode(err) === 'SLOT_UNAVAILABLE') this.slot.set(null);
      },
    });
  }

  protected async decline(): Promise<void> {
    const q = this.booking().quote;
    if (!q) return;
    const { confirmed, value } = await this.confirm.ask({
      title: 'Decline this quote?',
      message: `${this.first()} will be told. The inspection fee isn't refunded.`,
      confirmLabel: 'Decline quote',
      tone: 'danger',
      input: { label: 'Reason (optional)', placeholder: 'e.g. Too expensive for now' },
    });
    if (!confirmed) return;
    this.busy.set(true);
    this.api.declineQuote(q.id, value || null).subscribe({
      next: (b) => {
        this.busy.set(false);
        this.toast.success('Quote declined');
        this.changed.emit(b);
      },
      error: (err) => {
        this.busy.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }
}
