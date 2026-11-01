import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { Cart, User } from '../../core/api/models';
import { AuthStore } from '../../core/auth/auth-store';
import { CartStore } from '../../core/cart/cart-store';
import { ConfirmService } from '../../core/ui/feedback';
import CartPage from './cart.page';
import CheckoutPage from './checkout.page';

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

const customer = { id: 'u1', fullName: 'Demo Customer', email: 'c@example.com', phone: '9825000005', role: 'CUSTOMER' } as unknown as User;

const cart = (extra: Partial<Cart> = {}): Cart =>
  ({
    genieId: 'g1', genieName: 'Ravi Patel', itemsParam: '1:2', inspection: false, durationMinutes: 90,
    items: [{ serviceId: 1, name: 'Fan installation', quantity: 2, unitPrice: 269, amount: 538, durationMinutes: 45, inspectionFirst: false }],
    addressId: 'a1', addressLabel: 'Home · 12 Park Road', slotStart: '2026-11-03T05:30:00Z', paymentMethod: 'ONLINE', tipAmount: 20,
    notes: 'Ring the bell', couponCode: null, couponDiscount: null, estimate: null, total: 598,
    updatedAt: '2026-11-01T10:00:00Z', expiresAt: '2026-12-01T10:00:00Z', ...extra,
  }) as Cart;

function setUp(): { http: HttpTestingController; auth: AuthStore; carts: CartStore } {
  TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
  return { http: TestBed.inject(HttpTestingController), auth: TestBed.inject(AuthStore), carts: TestBed.inject(CartStore) };
}

describe('CartStore', () => {
  afterEach(() => localStorage.clear());

  it('keeps a guest cart in the browser and moves it into the account at login', async () => {
    localStorage.setItem('pg-guest-cart', JSON.stringify({
      genieId: 'g1', genieName: 'Ravi Patel', items: [{ serviceId: 1, quantity: 2, name: 'Fan installation' }], savedAt: new Date().toISOString(),
    }));
    const { http, auth, carts } = setUp();
    TestBed.tick();
    expect(carts.summary()).toEqual({ genieId: 'g1', genieName: 'Ravi Patel', label: 'Fan installation ×2', count: 1, guest: true });

    auth.applyLogin('t', customer);
    TestBed.tick();
    http.expectOne((r) => r.method === 'GET' && r.url === '/api/v1/cart').flush({ cart: null, notices: [] });
    const put = http.expectOne((r) => r.method === 'PUT' && r.url === '/api/v1/cart');
    expect(put.request.body).toMatchObject({ genieId: 'g1', items: [{ serviceId: 1, quantity: 2 }], replace: true });
    put.flush({ cart: cart(), notices: [] });
    http.expectOne((r) => r.method === 'GET' && r.url === '/api/v1/cart').flush({ cart: cart(), notices: ['The price changed from ₹578 to ₹598.'] });
    await wait(0);
    expect(localStorage.getItem('pg-guest-cart')).toBeNull();
    expect(carts.summary()).toMatchObject({ genieName: 'Ravi Patel', guest: false, count: 1 });
    expect(carts.notices()).toEqual(['The price changed from ₹578 to ₹598.']);
  });
});

describe('CheckoutPage with a cart', () => {
  beforeEach(() => {
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
  });

  async function open(genieId: string, saved: Cart) {
    const { http, auth } = setUp();
    auth.applyLogin('t', customer);
    TestBed.tick();
    http.expectOne('/api/v1/cart').flush({ cart: saved, notices: ['Coupon SAVE10 no longer applies: It has expired.'] });
    const fixture = TestBed.createComponent(CheckoutPage);
    fixture.componentRef.setInput('genieId', genieId);
    fixture.detectChanges();
    await wait(10);
    http.expectOne(`/api/v1/genies/${genieId}`).flush({
      genie: { id: genieId, fullName: genieId === 'g1' ? 'Ravi Patel' : 'Meera Joshi', online: true, avgRating: 4.8, ratingCount: 12 },
      services: [
        { serviceId: 1, name: 'Fan installation', durationMinutes: 45, price: 269 },
        { serviceId: 2, name: 'Switchboard repair', durationMinutes: 30, price: 219 },
      ],
      reviews: [],
    });
    for (const r of http.match('/api/v1/me/addresses')) {
      r.flush([
        { id: 'a0', label: 'Office', line1: '1 Ring Road', lat: 23.0, lng: 72.5, isDefault: true },
        { id: 'a1', label: 'Home', line1: '12 Park Road', lat: 23.03, lng: 72.52, isDefault: false },
      ]);
    }
    fixture.detectChanges();
    TestBed.tick();
    await wait(50);
    fixture.detectChanges();
    return { fixture, http, el: fixture.nativeElement as HTMLElement };
  }

  it('picks up where the customer left off and says what changed', async () => {
    const { el, fixture } = await open('g1', cart());
    expect(el.querySelector('[data-testid=checkout-from-cart]')?.textContent).toContain('We kept your booking from last time.');
    expect(el.textContent).toContain('Coupon SAVE10 no longer applies');
    expect(el.querySelector('[data-testid=qty-value-1]')?.textContent?.trim()).toBe('2');
    const page = fixture.componentInstance as unknown as { addressId(): string; method(): string; tip(): number; notes(): string; slot(): { start: string } | null };
    expect(page.addressId()).toBe('a1');
    expect(page.method()).toBe('ONLINE');
    expect(page.tip()).toBe(20);
    expect(page.notes()).toBe('Ring the bell');
    expect(page.slot()?.start).toBe('2026-11-03T05:30:00Z');
  });

  it('shows a changed price first and books only when the customer confirms it again', async () => {
    const { el, fixture, http } = await open('g1', cart());
    const estimate = (total: number) => ({
      serviceAmount: 538, distanceKm: 3, distanceMethod: 'ESTIMATE', travelFee: 40, tipAmount: 20, totalAmount: total, commissionRate: 0.05,
      commissionAmount: 0, genieEarning: 0, durationMinutes: 90, inspection: false, inspectionFee: 0,
      lines: [{ serviceId: 1, serviceName: 'Fan installation', quantity: 2, unitPrice: 269, amount: 538, durationMinutes: 45, commissionAmount: 0 }],
    });
    TestBed.tick();
    await wait(200);
    for (const r of http.match((x) => x.url.endsWith('/slot-days'))) r.flush([]);
    for (const r of http.match((x) => x.url === '/api/v1/coupons')) r.flush([]);
    for (const r of http.match((x) => x.url === '/api/v1/pricing/estimate')) r.flush(estimate(598));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const confirm = () => Array.from(el.querySelectorAll<HTMLButtonElement>('button')).find((b) => b.textContent?.includes('Confirm booking'))!;
    confirm().click();
    const first = http.expectOne('/api/v1/bookings');
    expect(first.request.body.expectedTotal).toBe(598);
    first.flush({ code: 'PRICE_CHANGED', detail: 'The price changed from ₹598 to ₹648. Please check it and confirm again.', newTotal: 648 },
      { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    TestBed.tick();
    await wait(200);
    for (const r of http.match((x) => x.url === '/api/v1/pricing/estimate')) r.flush(estimate(648));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=price-changed]')?.textContent).toContain('The price changed from ₹598 to ₹648');
    confirm().click();
    const second = http.expectOne('/api/v1/bookings');
    expect(second.request.body.expectedTotal).toBe(648);
  });

  it('asks before a booking with another Genie replaces the cart', async () => {
    await open('g2', cart());
    const confirm = TestBed.inject(ConfirmService);
    expect(confirm.current()?.title).toBe('Replace your cart?');
    expect(confirm.current()?.message).toContain('Ravi Patel (Fan installation ×2)');
    expect(confirm.current()?.confirmLabel).toBe('Clear cart and continue');
  });
});

describe('CartPage', () => {
  it('shows the saved booking, what changed, and continues to checkout', async () => {
    const { http, auth } = setUp();
    auth.applyLogin('t', customer);
    TestBed.tick();
    http.expectOne('/api/v1/cart').flush({ cart: cart(), notices: [] });
    const fixture = TestBed.createComponent(CartPage);
    fixture.detectChanges();
    http.expectOne('/api/v1/cart').flush({ cart: cart({ total: 648 }), notices: ['The price changed from ₹598 to ₹648.'] });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid=cart-notices]')?.textContent).toContain('The price changed from ₹598 to ₹648.');
    expect(el.querySelector('[data-testid=cart-item]')?.textContent).toContain('Fan installation ×2');
    expect(el.querySelector('[data-testid=cart-total]')?.textContent).toContain('648');
    expect(el.querySelector('[data-testid=cart-time]')?.textContent).toContain('Tue 3 Nov');
    expect(el.querySelector('[data-testid=cart-continue]')?.getAttribute('href')).toBe('/book/g1');
  });
});
