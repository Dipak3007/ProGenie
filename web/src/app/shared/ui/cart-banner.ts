import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { LucideArrowRight as ArrowRight, LucideDynamicIcon, LucideShoppingCart as ShoppingCart } from '@lucide/angular';

import { CartStore } from '../../core/cart/cart-store';

/** "Continue your booking with Ravi" (Phase 3.5E): shown on the home page while the cart holds a booking. */
@Component({
  selector: 'pg-cart-banner',
  imports: [RouterLink, LucideDynamicIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @if (summary(); as s) {
      <div class="bg-gold text-ink" data-testid="cart-banner">
        <a routerLink="/cart" class="container-page flex min-h-12 items-center gap-3 py-2 text-sm">
          <svg [lucideIcon]="Cart" [size]="18" class="shrink-0" aria-hidden="true"></svg>
          <span class="min-w-0 flex-1">
            <span class="font-semibold">Continue your booking with {{ first() }}</span>
            <span class="hidden sm:inline"> · {{ s.label }}</span>
          </span>
          <span class="inline-flex shrink-0 items-center gap-1 font-semibold underline-offset-2 hover:underline">
            Go to cart <svg [lucideIcon]="Arrow" [size]="16" aria-hidden="true"></svg>
          </span>
        </a>
      </div>
    }
  `,
})
export class CartBanner {
  private readonly carts = inject(CartStore);
  protected readonly summary = this.carts.summary;
  protected readonly first = computed(() => this.summary()?.genieName.split(' ')[0] ?? '');
  protected readonly Cart = ShoppingCart;
  protected readonly Arrow = ArrowRight;
}
