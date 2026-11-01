import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { Observable, catchError, map, of, switchMap, tap } from 'rxjs';

import { CustomerApi } from '../api/customer-api';
import { Cart, CartRequest, CartResponse } from '../api/models';
import { AuthStore } from '../auth/auth-store';
import { ServiceLineSel, itemsParam } from '../../shared/service-lines';

const GUEST_KEY = 'pg-guest-cart';

/** A guest's cart (Phase 3.5E): kept in this browser until they log in, then moved into their account. */
export interface GuestCart {
  genieId: string;
  genieName: string;
  items: (ServiceLineSel & { name: string })[];
  savedAt: string;
}

/** What the header, the home banner and the cart page show about the cart, guest or not. */
export interface CartSummary {
  genieId: string;
  genieName: string;
  label: string;
  count: number;
  guest: boolean;
}

/**
 * The customer's cart (Phase 3.5E). Logged-in customers: saved on the server (follows them across devices and
 * logins, re-checked on every read). Guests: kept in this browser and moved into the account at login (it replaces
 * the account's cart for another Genie, and replaces the services of a cart for the same Genie, keeping its address
 * and time).
 */
@Injectable({ providedIn: 'root' })
export class CartStore {
  private readonly api = inject(CustomerApi);
  private readonly auth = inject(AuthStore);

  private readonly _cart = signal<Cart | null>(null);
  private readonly _guest = signal<GuestCart | null>(readGuest());
  private readonly _loaded = signal(false);
  /** The account's cart (null when empty, for guests and for Genies/admins). */
  readonly cart = this._cart.asReadonly();
  readonly guest = this._guest.asReadonly();
  /** True once the account's cart has been fetched (or there is nothing to fetch). */
  readonly loaded = this._loaded.asReadonly();
  /** What the last re-check changed, shown once by the cart page or checkout. */
  readonly notices = signal<string[]>([]);

  private readonly isCustomer = computed(() => this.auth.role() === 'CUSTOMER');

  readonly summary = computed<CartSummary | null>(() => {
    const c = this._cart();
    if (c) {
      return { genieId: c.genieId, genieName: c.genieName, label: labelOf(c.items), count: c.items.length, guest: false };
    }
    const g = this._guest();
    if (g && !this.auth.isLoggedIn()) {
      return { genieId: g.genieId, genieName: g.genieName, label: labelOf(g.items), count: g.items.length, guest: true };
    }
    return null;
  });

  constructor() {
    // Log in: move a guest cart into the account, then load the account's cart. Log out: forget it.
    effect(() => {
      const customer = this.isCustomer();
      const userId = this.auth.user()?.id;
      untracked(() => {
        this._cart.set(null);
        this.notices.set([]);
        if (!customer || !userId) {
          this._loaded.set(!this.auth.isLoggedIn() || !customer);
          return;
        }
        this._loaded.set(false);
        const guest = this._guest();
        (guest ? this.adopt(guest) : of(null)).subscribe(() => this.refresh().subscribe());
      });
    });
  }

  /** Fetches the account's cart, re-checked; what changed lands in `notices`. */
  refresh(): Observable<CartResponse | null> {
    if (!this.isCustomer()) {
      this._loaded.set(true);
      return of(null);
    }
    return this.api.cart().pipe(
      tap((res) => {
        this._cart.set(res.cart ?? null);
        this.notices.set(res.notices);
        this._loaded.set(true);
      }),
      catchError(() => {
        this._loaded.set(true);
        return of(null);
      }),
    );
  }

  /** Saves the checkout. Errors (e.g. 409 CART_OTHER_GENIE) are passed on. */
  save(body: CartRequest): Observable<CartResponse> {
    return this.api.saveCart(body).pipe(tap((res) => this._cart.set(res.cart ?? null)));
  }

  clear(): Observable<void> {
    this._cart.set(null);
    this.notices.set([]);
    return this.isCustomer() ? this.api.clearCart().pipe(catchError(() => of(undefined))) : of(undefined);
  }

  /** The booking was made: the API has already deleted the cart. */
  booked(): void {
    this._cart.set(null);
    this.notices.set([]);
  }

  /** A guest picked services on a Genie's profile (before logging in to book). */
  saveGuest(cart: GuestCart): void {
    this._guest.set(cart);
    writeGuest(cart);
  }

  clearGuest(): void {
    this._guest.set(null);
    writeGuest(null);
  }

  private adopt(guest: GuestCart): Observable<null> {
    const items = guest.items.map(({ serviceId, quantity }) => ({ serviceId, quantity }));
    return this.api.cart().pipe(
      map((res) => res.cart ?? null),
      catchError(() => of(null)),
      map((current) => {
        const same = current?.genieId === guest.genieId;
        const body: CartRequest = {
          genieId: guest.genieId,
          items,
          addressId: same ? (current!.addressId ?? null) : null,
          slotStart: same && current!.itemsParam === itemsParam(items) ? (current!.slotStart ?? null) : null,
          paymentMethod: same ? ((current!.paymentMethod as CartRequest['paymentMethod']) ?? null) : null,
          tipAmount: same ? current!.tipAmount : 0,
          notes: same ? (current!.notes ?? null) : null,
          couponCode: same ? (current!.couponCode ?? null) : null,
          seenTotal: null,
          replace: true,
        };
        return body;
      }),
      switchMap((body) => this.api.saveCart(body).pipe(catchError(() => of(null)))),
      tap(() => this.clearGuest()),
      map(() => null),
    );
  }
}

function labelOf(items: readonly { name: string; quantity: number }[]): string {
  return items.map((i) => i.name + (i.quantity > 1 ? ` ×${i.quantity}` : '')).join(' + ');
}

function readGuest(): GuestCart | null {
  try {
    const raw = localStorage.getItem(GUEST_KEY);
    if (!raw) return null;
    const g = JSON.parse(raw) as GuestCart;
    const age = Date.now() - new Date(g.savedAt).getTime();
    return g.genieId && Array.isArray(g.items) && g.items.length && age < 30 * 86_400_000 ? g : null;
  } catch {
    return null;
  }
}

function writeGuest(cart: GuestCart | null): void {
  try {
    if (cart) localStorage.setItem(GUEST_KEY, JSON.stringify(cart));
    else localStorage.removeItem(GUEST_KEY);
  } catch {
    // private window or blocked storage: the guest cart just isn't kept
  }
}
