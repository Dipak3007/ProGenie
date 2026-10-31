import { ChangeDetectionStrategy, Component, computed, effect, inject, input, model, signal, untracked } from '@angular/core';
import { CurrencyPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, of, switchMap } from 'rxjs';
import {
  LucideBadgePercent as BadgePercent,
  LucideCircleCheck as CircleCheck,
  LucideDynamicIcon,
  LucideX as X,
} from '@lucide/angular';

import { errorMessage } from '../../core/api/api';
import { CustomerApi } from '../../core/api/customer-api';
import { CouponOffer } from '../../core/api/models';
import { Spinner } from '../../shared/ui/kit';

/**
 * Coupons at checkout (Phase 3B): the public coupons for this order (tap to apply; the ones that can't be used say
 * why) and a box for a typed code. The applied coupon is checked again whenever the service or address changes.
 * The discount is on the service price only; the booking re-checks it on the server.
 */
@Component({
  selector: 'pg-coupon-picker',
  imports: [CurrencyPipe, FormsModule, LucideDynamicIcon, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (applied(); as a) {
      <div class="flex items-center gap-3 rounded-2xl bg-emerald-50 p-4 ring-1 ring-emerald-200">
        <svg [lucideIcon]="CircleCheck" [size]="22" class="shrink-0 text-emerald-700" aria-hidden="true"></svg>
        <div class="min-w-0 flex-1">
          <p class="font-semibold text-emerald-900">{{ a.code }} applied · you save {{ a.discount | currency: 'INR' }}</p>
          <p class="truncate text-sm text-emerald-900/80">{{ a.title }}</p>
        </div>
        <button type="button" class="btn-ghost min-h-10 px-3" (click)="remove()" [attr.aria-label]="'Remove coupon ' + a.code">
          <svg [lucideIcon]="X" [size]="16" aria-hidden="true"></svg> Remove
        </button>
      </div>
    } @else {
      @if (offers().length) {
        <ul class="grid gap-2 sm:grid-cols-2" aria-label="Available coupons">
          @for (o of offers(); track o.code) {
            <li>
              <button
                type="button"
                class="flex w-full items-start gap-3 rounded-2xl border p-3 text-left transition"
                [class]="o.applicable ? 'border-dashed border-brand bg-brand-mist/30 hover:bg-brand-mist/60' : 'cursor-not-allowed border-line bg-surface opacity-70'"
                [disabled]="!o.applicable || busy()"
                (click)="apply(o)"
              >
                <svg [lucideIcon]="BadgePercent" [size]="20" class="mt-0.5 shrink-0 text-brand" aria-hidden="true"></svg>
                <span class="min-w-0 flex-1">
                  <span class="flex flex-wrap items-baseline justify-between gap-x-2">
                    <span class="font-display font-bold tracking-wide">{{ o.code }}</span>
                    @if (o.applicable) {
                      <span class="text-sm font-semibold text-emerald-700">Save {{ o.discount | currency: 'INR' }}</span>
                    }
                  </span>
                  <span class="block text-sm">{{ o.title }}</span>
                  <span class="block text-xs text-muted">{{ o.applicable ? (o.validUntil ? 'Valid till ' + day(o.validUntil) : 'Tap to apply') : o.reason }}</span>
                </span>
              </button>
            </li>
          }
        </ul>
      }
      <form class="mt-3 flex gap-2" (ngSubmit)="applyTyped()">
        <label class="sr-only" for="coupon-code">Coupon code</label>
        <input
          id="coupon-code"
          name="coupon"
          class="field flex-1 uppercase"
          maxlength="20"
          autocomplete="off"
          placeholder="Have a code?"
          [(ngModel)]="code"
        />
        <button type="submit" class="btn-ghost min-h-11 px-5" [disabled]="busy() || !code.trim()">
          @if (busy()) {
            <pg-spinner />
          }
          Apply
        </button>
      </form>
    }
    @if (message(); as m) {
      <p class="mt-2 text-sm text-rose-700" role="alert">{{ m }}</p>
    }
  `,
})
export class CouponPicker {
  readonly genieId = input.required<string>();
  readonly serviceId = input<number | null>(null);
  readonly addressId = input<string | null>(null);
  /** The coupon applied to the booking (two-way). */
  readonly applied = model<CouponOffer | null>(null);

  private readonly api = inject(CustomerApi);
  protected readonly busy = signal(false);
  protected readonly message = signal<string | null>(null);
  protected code = '';

  private readonly context = computed(
    () => {
      const serviceId = this.serviceId();
      const addressId = this.addressId();
      return serviceId && addressId ? { genieId: this.genieId(), serviceId, addressId } : null;
    },
    { equal: (a, b) => a?.genieId === b?.genieId && a?.serviceId === b?.serviceId && a?.addressId === b?.addressId },
  );

  protected readonly offers = toSignal(
    toObservable(this.context).pipe(
      switchMap((c) => (c ? this.api.coupons(c.genieId, c.serviceId, c.addressId).pipe(catchError(() => of([]))) : of([]))),
    ),
    { initialValue: [] as CouponOffer[] },
  );

  constructor() {
    // A different service or address can change the discount or make the coupon invalid: check it again.
    effect(() => {
      const c = this.context();
      untracked(() => {
        const current = this.applied();
        if (!current) return;
        if (!c) {
          this.applied.set(null);
          return;
        }
        this.api.checkCoupon(current.code, c.genieId, c.serviceId, c.addressId).subscribe({
          next: (fresh) => this.applied.set(fresh),
          error: (err) => {
            this.applied.set(null);
            this.message.set(errorMessage(err));
          },
        });
      });
    });
  }

  protected apply(o: CouponOffer): void {
    this.check(o.code);
  }

  protected applyTyped(): void {
    this.check(this.code.trim());
  }

  protected remove(): void {
    this.applied.set(null);
    this.message.set(null);
  }

  /** Called by the page when the booking refused the coupon. */
  reject(reason: string): void {
    this.applied.set(null);
    this.message.set(reason);
  }

  private check(code: string): void {
    const c = this.context();
    if (!c || !code) {
      this.message.set('Choose a service and an address first.');
      return;
    }
    this.busy.set(true);
    this.message.set(null);
    this.api.checkCoupon(code, c.genieId, c.serviceId, c.addressId).subscribe({
      next: (offer) => {
        this.busy.set(false);
        this.code = '';
        this.applied.set(offer);
      },
      error: (err) => {
        this.busy.set(false);
        this.message.set(errorMessage(err, 'This coupon can’t be used.'));
      },
    });
  }

  protected day(iso: string): string {
    return new Date(iso).toLocaleDateString('en-IN', { day: 'numeric', month: 'short' });
  }

  protected readonly BadgePercent = BadgePercent;
  protected readonly CircleCheck = CircleCheck;
  protected readonly X = X;
}
