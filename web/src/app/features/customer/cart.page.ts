import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { CurrencyPipe, DatePipe, NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { LucideShoppingCart as ShoppingCart } from '@lucide/angular';

import { AuthStore } from '../../core/auth/auth-store';
import { CartStore } from '../../core/cart/cart-store';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { IST } from '../../shared/format';
import { durationText } from '../../shared/service-lines';
import { Avatar } from '../../shared/ui/avatar';
import { EmptyState, Skeleton } from '../../shared/ui/kit';

/**
 * The cart (Phase 3.5E): the booking the customer started and left, re-checked (what changed is listed), with
 * "Continue to checkout". Guests see what they picked and log in to continue.
 */
@Component({
  selector: 'pg-cart-page',
  imports: [RouterLink, CurrencyPipe, DatePipe, NgTemplateOutlet, Avatar, EmptyState, Skeleton],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container-page max-w-3xl pb-16 pt-6 sm:pt-8">
      <h1 class="text-3xl font-bold sm:text-4xl">Your cart</h1>

      @if (auth.role() === 'GENIE' || auth.role() === 'ADMIN') {
        <pg-empty class="mt-6 block" title="Carts are for customers" message="Log in with a customer account to book a Genie." [icon]="Cart" />
      } @else if (!auth.isLoggedIn()) {
        @if (carts.guest(); as g) {
          <section class="card mt-6 p-5 sm:p-6" data-testid="cart">
            <div class="flex items-center gap-3">
              <pg-avatar [name]="g.genieName" [size]="48" />
              <div class="min-w-0">
                <p class="text-sm text-muted">Booking with</p>
                <p class="truncate text-lg font-bold">{{ g.genieName }}</p>
              </div>
            </div>
            <ul class="mt-4 space-y-1 text-sm">
              @for (i of g.items; track i.serviceId) {
                <li>{{ i.name }}{{ i.quantity > 1 ? ' ×' + i.quantity : '' }}</li>
              }
            </ul>
            <div class="mt-5 flex flex-col gap-2 sm:flex-row">
              <a routerLink="/login" [queryParams]="{ returnUrl: '/book/' + g.genieId }" class="btn-gold min-h-12 flex-1">Log in to continue</a>
              <button type="button" class="btn-ghost min-h-12" (click)="carts.clearGuest()">Empty cart</button>
            </div>
          </section>
        } @else {
          <ng-container *ngTemplateOutlet="emptyState" />
        }
      } @else if (!carts.loaded()) {
        <pg-skeleton class="mt-6 block" [rows]="4" />
      } @else if (carts.cart(); as c) {
        @if (notices().length) {
          <div class="mt-6 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900" role="status" data-testid="cart-notices">
            <p class="font-semibold">Your cart was updated</p>
            @for (n of notices(); track n) {
              <p class="mt-1">{{ n }}</p>
            }
          </div>
        }
        <section class="card mt-6 p-5 sm:p-6" data-testid="cart">
          <div class="flex items-center gap-3">
            <pg-avatar [name]="c.genieName" [size]="48" />
            <div class="min-w-0">
              <p class="text-sm text-muted">Booking with</p>
              <a [routerLink]="['/genies', c.genieId]" class="truncate text-lg font-bold hover:text-brand">{{ c.genieName }}</a>
            </div>
          </div>

          <dl class="mt-5 space-y-2 text-sm [&_dd]:shrink-0 [&_dd]:whitespace-nowrap">
            @for (i of c.items; track i.serviceId) {
              <div class="flex justify-between gap-3" data-testid="cart-item">
                <dt class="min-w-0">{{ i.name }}{{ i.quantity > 1 ? ' ×' + i.quantity : '' }}</dt>
                <dd>{{ c.inspection ? '' : (i.amount | currency: 'INR') }}</dd>
              </div>
            }
          </dl>
          <p class="mt-2 text-xs text-muted">
            {{ c.inspection ? 'An inspection visit first (30 min), then a priced quote.' : 'About ' + duration(c.durationMinutes) }}
          </p>

          <dl class="mt-5 grid gap-3 border-t border-line pt-4 text-sm sm:grid-cols-2">
            <div>
              <dt class="text-muted">Address</dt>
              <dd class="font-medium">{{ c.addressLabel ?? 'Not chosen yet' }}</dd>
            </div>
            <div>
              <dt class="text-muted">Time</dt>
              <dd class="font-medium" data-testid="cart-time">{{ c.slotStart ? (c.slotStart | date: 'EEE d MMM, h:mm a' : ist) : 'Not chosen yet' }}</dd>
            </div>
            <div>
              <dt class="text-muted">Payment</dt>
              <dd class="font-medium">{{ c.paymentMethod === 'ONLINE' ? 'Online after the job' : 'Cash after the job' }}</dd>
            </div>
            @if (c.couponCode) {
              <div>
                <dt class="text-muted">Coupon</dt>
                <dd class="font-medium">{{ c.couponCode }}@if (c.couponDiscount) { (− {{ c.couponDiscount | currency: 'INR' }}) }</dd>
              </div>
            }
          </dl>

          @if (c.total !== null && c.total !== undefined) {
            <div class="mt-5 flex items-center justify-between rounded-2xl bg-surface px-4 py-3">
              <span class="text-sm text-muted">Total today</span>
              <span class="font-display text-xl font-bold" data-testid="cart-total">{{ c.total | currency: 'INR' }}</span>
            </div>
          }

          <div class="mt-5 flex flex-col gap-2 sm:flex-row">
            <a [routerLink]="['/book', c.genieId]" class="btn-gold min-h-12 flex-1" data-testid="cart-continue">Continue to checkout</a>
            <button type="button" class="btn-ghost min-h-12" (click)="empty()">Empty cart</button>
          </div>
          <p class="mt-3 text-xs text-muted">Prices, times and coupons are checked again when you book. Your cart is kept for 30 days.</p>
        </section>
      } @else {
        @if (notices().length) {
          <div class="mt-6 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900" role="status" data-testid="cart-notices">
            @for (n of notices(); track n) {
              <p>{{ n }}</p>
            }
          </div>
        }
        <ng-container *ngTemplateOutlet="emptyState" />
      }

      <ng-template #emptyState>
        <pg-empty class="mt-6 block" title="Your cart is empty" message="Pick a Genie and services; your booking is kept here until you finish it." [icon]="Cart">
          <a routerLink="/services" class="btn-primary">Browse services</a>
        </pg-empty>
      </ng-template>
    </div>
  `,
})
export default class CartPage {
  protected readonly auth = inject(AuthStore);
  protected readonly carts = inject(CartStore);
  private readonly confirm = inject(ConfirmService);
  private readonly toast = inject(ToastService);
  protected readonly notices = signal<string[]>([]);
  protected readonly ist = IST;

  constructor() {
    // Re-check on every visit; show what changed once.
    if (this.auth.role() === 'CUSTOMER') {
      this.carts.refresh().subscribe((res) => {
        this.notices.set(res?.notices ?? []);
        this.carts.notices.set([]);
      });
    }
  }

  protected duration(minutes: number): string {
    return durationText(minutes);
  }

  protected async empty(): Promise<void> {
    const { confirmed } = await this.confirm.ask({
      title: 'Empty your cart?',
      message: 'The services and choices you saved will be removed.',
      confirmLabel: 'Empty cart',
      tone: 'danger',
    });
    if (!confirmed) return;
    this.carts.clear().subscribe(() => {
      this.notices.set([]);
      this.toast.success('Cart emptied');
    });
  }

  protected readonly Cart = ShoppingCart;
}
