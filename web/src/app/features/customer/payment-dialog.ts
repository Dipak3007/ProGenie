import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import {
  LucideCreditCard as CreditCard,
  LucideDynamicIcon,
  LucideShieldCheck as ShieldCheck,
} from '@lucide/angular';

import { errorCode, errorMessage } from '../../core/api/api';
import { AuthStore } from '../../core/auth/auth-store';
import { loadRazorpay } from '../../shared/razorpay';
import { CustomerApi } from '../../core/api/customer-api';
import { PaymentOrder } from '../../core/api/models';
import { Dialog } from '../../shared/ui/dialog';
import { Spinner } from '../../shared/ui/kit';

/**
 * Online payment for a completed job or a late-cancellation fee.
 *
 * Flow: create an order on the server → the gateway's checkout → the server verifies the gateway's signed result.
 * Locally the gateway is ProGenie's fake one, so "checkout" is a test screen whose buttons ask the server to
 * simulate the gateway (the browser never decides that a payment succeeded).
 */
@Component({
  selector: 'pg-payment-dialog',
  imports: [CurrencyPipe, Dialog, LucideDynamicIcon, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-dialog [open]="open()" heading="Pay online" [subheading]="order()?.bookingRef ?? null" size="sm" (closed)="closed.emit()">
      @if (loading()) {
        <p class="flex items-center gap-2 py-6 text-sm text-muted"><pg-spinner /> Preparing a secure checkout…</p>
      } @else if (error() && !order()) {
        <p class="rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800">{{ error() }}</p>
        <button type="button" class="btn-ghost mt-4 w-full" (click)="createOrder()">Try again</button>
      } @else if (order(); as o) {
        <div class="rounded-2xl bg-navy p-5 text-white">
          <p class="text-sm text-brand-soft">{{ o.purpose === 'CANCELLATION_FEE' ? 'Late-cancellation fee' : 'Amount due' }}</p>
          <p class="mt-1 font-display text-4xl font-bold">{{ o.amount | currency: o.currency : 'symbol' : '1.0-2' }}</p>
          <p class="mt-3 flex items-center gap-2 text-xs text-brand-mist">
            <svg [lucideIcon]="ShieldCheck" [size]="14" class="text-gold" aria-hidden="true"></svg>
            Verified by the server with the gateway's signature
          </p>
        </div>

        @if (o.provider === 'fake') {
          <div class="mt-4 rounded-2xl border border-dashed border-gold/60 bg-gold/10 p-4 text-sm">
            <p class="font-semibold">Test checkout</p>
            <p class="mt-1 text-ink/75">Online payments run on ProGenie's test gateway on this server. No real money moves.</p>
          </div>
          @if (error()) {
            <p class="mt-3 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ error() }}</p>
          }
          <div class="mt-5 grid gap-2">
            <button type="button" class="btn-gold min-h-12" [disabled]="paying()" (click)="simulate('success')">
              @if (paying()) {
                <pg-spinner />
              } @else {
                <svg [lucideIcon]="CreditCard" [size]="18" aria-hidden="true"></svg>
              }
              Pay {{ o.amount | currency: o.currency : 'symbol' : '1.0-2' }}
            </button>
            <button type="button" class="btn-ghost" [disabled]="paying()" (click)="simulate('failure')">Simulate a declined payment</button>
          </div>
        } @else if (o.provider === 'razorpay' && o.keyId) {
          <p class="mt-4 text-sm text-muted">Pay with UPI, card or netbanking in Razorpay's secure window.</p>
          @if (error()) {
            <p class="mt-3 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ error() }}</p>
          }
          <button type="button" class="btn-gold mt-5 min-h-12 w-full" [disabled]="paying()" (click)="razorpay(o)">
            @if (paying()) {
              <pg-spinner />
            } @else {
              <svg [lucideIcon]="CreditCard" [size]="18" aria-hidden="true"></svg>
            }
            Pay {{ o.amount | currency: o.currency : 'symbol' : '1.0-2' }}
          </button>
        } @else {
          <p class="mt-4 text-sm text-muted">Online payment isn't available right now. Please pay the Genie in cash.</p>
        }
      }
    </pg-dialog>
  `,
})
export class PaymentDialog {
  readonly open = input(false);
  readonly bookingId = input.required<string>();
  readonly closed = output<void>();
  /** Emitted after the server confirmed the payment. */
  readonly paid = output<void>();

  private readonly api = inject(CustomerApi);
  private readonly auth = inject(AuthStore);
  protected readonly order = signal<PaymentOrder | null>(null);
  protected readonly loading = signal(false);
  protected readonly paying = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      if (this.open()) untracked(() => this.createOrder());
    });
  }

  protected createOrder(): void {
    this.order.set(null);
    this.error.set(null);
    this.loading.set(true);
    this.api.paymentOrder(this.bookingId()).subscribe({
      next: (o) => {
        this.loading.set(false);
        this.order.set(o);
      },
      error: (err) => {
        this.loading.set(false);
        if (errorCode(err) === 'ALREADY_PAID' || errorCode(err) === 'NOTHING_TO_PAY') {
          this.paid.emit();
          return;
        }
        this.error.set(errorMessage(err, 'Could not start the payment.'));
      },
    });
  }

  protected simulate(outcome: 'success' | 'failure'): void {
    const o = this.order();
    if (!o) return;
    this.paying.set(true);
    this.error.set(null);
    this.api.simulatePayment(o.paymentId, outcome).subscribe({
      next: (payment) => {
        this.paying.set(false);
        if (payment.status === 'SUCCEEDED') {
          this.paid.emit();
        } else {
          this.error.set(`Payment failed: ${payment.failureReason ?? 'declined'}. You can try again.`);
          // A failed order cannot be reused; the next attempt gets a fresh one.
          this.api.paymentOrder(this.bookingId()).subscribe({ next: (fresh) => this.order.set(fresh), error: () => undefined });
        }
      },
      error: (err) => {
        this.paying.set(false);
        this.error.set(errorMessage(err, 'Payment could not be completed.'));
      },
    });
  }

  /** Opens Razorpay Checkout; its signed result is checked by the server before anything is marked paid. */
  protected async razorpay(o: PaymentOrder): Promise<void> {
    this.paying.set(true);
    this.error.set(null);
    try {
      const Razorpay = await loadRazorpay();
      const user = this.auth.user();
      const checkout = new Razorpay({
        key: o.keyId!,
        order_id: o.orderId,
        amount: Math.round(o.amount * 100),
        currency: o.currency,
        name: 'ProGenie',
        description: `${o.purpose === 'CANCELLATION_FEE' ? 'Cancellation fee' : 'Booking'} ${o.bookingRef}`,
        prefill: { name: user?.fullName, email: user?.email ?? undefined, contact: user?.phone },
        theme: { color: '#4F46E5' },
        handler: (res) =>
          this.api.confirmPayment(o.paymentId, { providerPaymentId: res.razorpay_payment_id, signature: res.razorpay_signature }).subscribe({
            next: () => {
              this.paying.set(false);
              this.paid.emit();
            },
            error: (err) => {
              this.paying.set(false);
              this.error.set(errorMessage(err, 'We could not verify the payment. If money was taken, it will be confirmed automatically.'));
            },
          }),
        modal: { ondismiss: () => this.paying.set(false) },
      });
      checkout.on('payment.failed', (res) => {
        this.paying.set(false);
        this.error.set(`Payment failed: ${res.error.description ?? res.error.reason ?? 'declined'}. You can try again.`);
      });
      checkout.open();
    } catch (e) {
      this.paying.set(false);
      this.error.set((e as Error).message);
    }
  }

  protected readonly CreditCard = CreditCard;
  protected readonly ShieldCheck = ShieldCheck;
}
