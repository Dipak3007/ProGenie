import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { CurrencyPipe, DatePipe, LowerCasePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { LucideArrowLeft as ArrowLeft, LucideDynamicIcon } from '@lucide/angular';

import { API, errorMessage } from '../../core/api/api';
import { AdminApi } from '../../core/api/admin-api';
import { BookingDetail, Payment, Receipt, Refund, RefundRequest } from '../../core/api/models';
import { SupportApi } from '../../core/api/support-api';
import { saveBlob } from '../../shared/format';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { Avatar } from '../../shared/ui/avatar';
import { PriceSummary, Timeline } from '../../shared/ui/booking-parts';
import { Dialog } from '../../shared/ui/dialog';
import { EmptyState, Skeleton, Spinner } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';
import { RefundFields, newRefund, refundValid, refundable } from './refund-fields';

@Component({
  selector: 'pg-admin-booking-page',
  imports: [RouterLink, CurrencyPipe, DatePipe, LowerCasePipe, FormsModule, LucideDynamicIcon, Avatar, Dialog, EmptyState, PriceSummary, RefundFields, Skeleton, Spinner, StatusBadge, Timeline],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <a routerLink="/admin/bookings" class="inline-flex items-center gap-1 text-sm font-semibold text-muted hover:text-brand">
      <svg [lucideIcon]="ArrowLeft" [size]="16" aria-hidden="true"></svg> Bookings
    </a>

    @if (detail.value(); as b) {
      <div class="mt-3 flex flex-wrap items-center gap-x-3 gap-y-2">
        <h1 class="text-2xl font-bold sm:text-3xl">{{ b.bookingRef }}</h1>
        <pg-status [status]="b.status" />
        @if (b.cancellationFeeStatus) {
          <pg-status [status]="b.cancellationFeeStatus" [label]="'Fee ' + b.cancellationFeeStatus.toLowerCase()" />
        }
      </div>
      <p class="mt-1 text-sm text-muted">{{ b.serviceName }} · {{ b.categoryName }} · {{ b.slotStart | date: 'EEE d MMM y, h:mm a' }}</p>

      <div class="mt-6 grid grid-cols-[minmax(0,1fr)] gap-6 xl:grid-cols-[minmax(0,1fr)_22rem]">
        <div class="min-w-0 space-y-6">
          <section class="card grid gap-5 p-5 sm:grid-cols-2">
            @for (party of [{ role: 'Customer', p: b.customer }, { role: 'Genie', p: b.genie }]; track party.role) {
              <div class="flex items-center gap-3">
                <pg-avatar [name]="party.p.name" [size]="44" />
                <div class="min-w-0">
                  <p class="font-semibold">{{ party.p.name }}</p>
                  <p class="text-sm text-muted">{{ party.role }}{{ party.p.phone ? ' · ' + party.p.phone : '' }}</p>
                  @if (party.role === 'Genie') {
                    <a [routerLink]="['/admin/genies', party.p.id]" class="text-xs font-semibold text-brand hover:underline">Open Genie</a>
                  }
                </div>
              </div>
            }
            <div class="sm:col-span-2">
              <p class="text-xs font-semibold uppercase tracking-wider text-muted">Address</p>
              @if (b.address; as a) {
                <p class="mt-1 text-sm">{{ a.line1 }}{{ a.line2 ? ', ' + a.line2 : '' }}{{ a.area ? ', ' + a.area : '' }}, {{ a.city }} {{ a.pincode }}</p>
              }
            </div>
            @if (b.notes) {
              <div class="sm:col-span-2">
                <p class="text-xs font-semibold uppercase tracking-wider text-muted">Customer note</p>
                <p class="mt-1 text-sm">{{ b.notes }}</p>
              </div>
            }
            @if (b.cancelReason) {
              <div class="sm:col-span-2">
                <p class="text-xs font-semibold uppercase tracking-wider text-muted">Cancellation / decline reason</p>
                <p class="mt-1 text-sm">{{ b.cancelReason }} ({{ b.cancelledByRole | lowercase }})</p>
              </div>
            }
          </section>

          <section class="card p-5" aria-labelledby="h-pay">
            <h2 id="h-pay" class="text-lg font-bold">Payments and refunds</h2>
            @if (payments.value(); as list) {
              <ul class="mt-4 divide-y divide-line">
                @for (p of list; track p.id) {
                  <li class="flex flex-wrap items-center gap-x-4 gap-y-2 py-3 text-sm">
                    <div class="min-w-0 flex-1">
                      <p class="font-semibold">{{ p.purpose === 'CANCELLATION_FEE' ? 'Cancellation fee' : 'Booking' }} · {{ p.amount | currency: 'INR' }}</p>
                      <p class="text-muted">
                        {{ p.method }}{{ p.provider ? ' · ' + p.provider : '' }} · {{ p.createdAt | date: 'd MMM, h:mm a' }}
                        @if (p.refundedAmount > 0) {
                          · {{ p.refundedAmount | currency: 'INR' }} refunded
                        }
                      </p>
                    </div>
                    <pg-status [status]="p.status" />
                    @if (p.status === 'SUCCEEDED' && refundableAmount(p) > 0) {
                      <button type="button" class="btn-ghost min-h-9 px-3 text-sm" (click)="openRefund(p)">Refund…</button>
                    }
                  </li>
                } @empty {
                  <li class="py-3 text-sm text-muted">No payments yet.</li>
                }
              </ul>
            }
            @if (refunds.value()?.length) {
              <h3 class="mt-5 text-sm font-semibold uppercase tracking-wider text-muted">Refunds</h3>
              <ul class="mt-2 divide-y divide-line">
                @for (r of refunds.value(); track r.id) {
                  <li class="flex flex-wrap items-center gap-x-4 gap-y-1 py-3 text-sm">
                    <div class="min-w-0 flex-1">
                      <p class="font-semibold">{{ r.amount | currency: 'INR' }} · {{ r.method === 'GATEWAY' ? 'to original method' : 'manual ' + (r.manualReference ?? '') }}</p>
                      <p class="text-muted">
                        Genie {{ r.genieShare | currency: 'INR' }} · ProGenie {{ r.platformShare | currency: 'INR' }} · {{ r.createdAt | date: 'd MMM, h:mm a' }}
                      </p>
                      <p class="text-muted">{{ r.reason }}</p>
                      @if (r.failureReason) {
                        <p class="text-rose-700">{{ r.failureReason }}</p>
                      }
                    </div>
                    <pg-status [status]="r.status" />
                  </li>
                }
              </ul>
            }
            @if (receipts.value()?.length) {
              <h3 class="mt-5 text-sm font-semibold uppercase tracking-wider text-muted">Receipts</h3>
              <ul class="mt-2 flex flex-wrap gap-2">
                @for (rc of receipts.value(); track rc.id) {
                  <li>
                    <button type="button" class="chip hover:border-brand hover:text-brand" (click)="download(rc)">
                      {{ rc.kind === 'CREDIT_NOTE' ? 'Credit note' : 'Receipt' }} {{ rc.number }} · {{ rc.amount | currency: 'INR' }}
                    </button>
                  </li>
                }
              </ul>
            }
          </section>

          <section class="card p-5" aria-labelledby="h-h">
            <h2 id="h-h" class="text-lg font-bold">Timeline</h2>
            <pg-timeline class="mt-4" [items]="b.history" />
          </section>
        </div>

        <aside class="space-y-6 xl:sticky xl:top-[calc(var(--header-h)+2.5rem)] xl:self-start">
          <section class="card p-5">
            <div class="flex items-center justify-between gap-3">
              <h2 class="text-lg font-bold">Money</h2>
              <pg-status [status]="b.paymentStatus" [label]="b.paymentMethod + ' · ' + b.paymentStatus.toLowerCase()" />
            </div>
            <pg-price-summary class="mt-4" [booking]="b" audience="ADMIN" />
          </section>

          @if (canCancel(b) || b.cancellationFeeStatus === 'DUE') {
            <section class="card space-y-3 p-5">
              <h2 class="text-lg font-bold">Support actions</h2>
              @if (canCancel(b)) {
                <button type="button" class="btn w-full border border-rose-200 bg-white text-rose-700 hover:bg-rose-50" (click)="cancelOpen.set(true)">Cancel booking</button>
              }
              @if (b.cancellationFeeStatus === 'DUE') {
                <button type="button" class="btn-ghost w-full" [disabled]="busy()" (click)="waive()">Waive the {{ b.cancellationFee | currency: 'INR' : 'symbol' : '1.0-0' }} fee</button>
              }
            </section>
          }
        </aside>
      </div>

      <pg-dialog [open]="cancelOpen()" heading="Cancel on behalf of ProGenie" size="sm" (closed)="cancelOpen.set(false)">
        <form class="space-y-4" (ngSubmit)="cancel()">
          <div>
            <label class="label" for="c-reason">Reason (both sides see it)</label>
            <textarea id="c-reason" name="reason" class="field min-h-24" maxlength="500" [(ngModel)]="reason" required></textarea>
          </div>
          <label class="flex items-center gap-3 text-sm">
            <input type="checkbox" name="waive" class="size-5 rounded accent-brand" [(ngModel)]="waiveFee" />
            Don't charge the customer a late-cancellation fee
          </label>
          <div class="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            <button type="button" class="btn-ghost" (click)="cancelOpen.set(false)">Back</button>
            <button type="submit" class="btn bg-rose-600 text-white hover:bg-rose-700" [disabled]="busy() || !reason.trim()">
              @if (busy()) {
                <pg-spinner />
              }
              Cancel booking
            </button>
          </div>
        </form>
      </pg-dialog>
      <pg-dialog [open]="!!refundPayment()" heading="Refund" [subheading]="b.bookingRef" size="lg" (closed)="refundPayment.set(null)">
        @if (refundPayment(); as p) {
          <form (ngSubmit)="submitRefund(p)">
            <pg-refund-fields [payment]="p" [(value)]="refund" />
            <div class="mt-5 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
              <button type="button" class="btn-ghost" (click)="refundPayment.set(null)">Cancel</button>
              <button type="submit" class="btn-primary" [disabled]="busy() || !canRefund(p)">
                @if (busy()) {
                  <pg-spinner />
                }
                Refund {{ (refund().amount ?? 0) | currency: 'INR' }}
              </button>
            </div>
          </form>
        }
      </pg-dialog>
    } @else if (detail.isLoading()) {
      <pg-skeleton class="mt-6 block" [rows]="3" />
    } @else if (detail.error()) {
      <pg-empty class="mt-6 block" title="Booking not found" />
    }
  `,
})
export default class AdminBookingPage {
  readonly id = input.required<string>();

  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  private readonly support = inject(SupportApi);

  protected readonly payments = httpResource<Payment[]>(() => `${API}/admin/bookings/${this.id()}/payments`);
  protected readonly refunds = httpResource<Refund[]>(() => `${API}/admin/bookings/${this.id()}/refunds`);
  protected readonly receipts = httpResource<Receipt[]>(() => `${API}/admin/bookings/${this.id()}/receipts`);
  protected readonly refundPayment = signal<Payment | null>(null);
  protected readonly refund = signal<RefundRequest>({ amount: null, liability: 'GENIE' });
  protected readonly refundableAmount = refundable;

  protected readonly detail = httpResource<BookingDetail>(() => `${API}/admin/bookings/${this.id()}`);
  protected readonly busy = signal(false);
  protected readonly cancelOpen = signal(false);
  protected reason = '';
  protected waiveFee = true;

  protected openRefund(p: Payment): void {
    this.refund.set(newRefund(p));
    this.refundPayment.set(p);
  }

  protected canRefund(p: Payment): boolean {
    return refundValid(this.refund(), p) && !!this.refund().reason?.trim();
  }

  protected submitRefund(p: Payment): void {
    if (!this.canRefund(p)) return;
    this.busy.set(true);
    this.api.refund(p.id, this.refund()).subscribe({
      next: (r) => {
        this.busy.set(false);
        this.refundPayment.set(null);
        this.payments.reload();
        this.refunds.reload();
        this.detail.reload();
        setTimeout(() => this.receipts.reload(), 800);
        this.toast.success(r.status === 'FAILED' ? 'The gateway refused the refund. See the refunds list.' : 'Refund recorded. The customer was told.');
      },
      error: (err) => {
        this.busy.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected download(rc: Receipt): void {
    this.support.receiptPdf(rc.id).subscribe({
      next: (blob) => saveBlob(blob, `${rc.number}.pdf`),
      error: (err) => this.toast.error(errorMessage(err)),
    });
  }

  protected canCancel(b: BookingDetail): boolean {
    return ['REQUESTED', 'ACCEPTED', 'IN_PROGRESS'].includes(b.status);
  }

  protected cancel(): void {
    this.busy.set(true);
    this.api.cancelBooking(this.id(), this.reason.trim(), this.waiveFee).subscribe({
      next: (b) => {
        this.busy.set(false);
        this.cancelOpen.set(false);
        this.detail.set(b);
        this.toast.success('Booking cancelled. Both sides were notified.');
      },
      error: (err) => {
        this.busy.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected async waive(): Promise<void> {
    const { confirmed } = await this.confirm.ask({ title: 'Waive the cancellation fee?', message: 'The customer can book again straight away.', confirmLabel: 'Waive fee' });
    if (!confirmed) return;
    this.busy.set(true);
    this.api.waiveFee(this.id()).subscribe({
      next: (b) => {
        this.busy.set(false);
        this.detail.set(b);
        this.toast.success('Fee waived');
      },
      error: (err) => {
        this.busy.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected readonly ArrowLeft = ArrowLeft;
}
