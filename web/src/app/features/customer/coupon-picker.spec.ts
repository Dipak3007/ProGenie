import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { CouponOffer } from '../../core/api/models';
import { CouponPicker } from './coupon-picker';

const offer = (code: string, applicable: boolean, extra: Partial<CouponOffer> = {}): CouponOffer => ({
  code, title: code + ' title', description: null, discountType: 'FLAT', discountValue: 50, maxDiscount: null, minOrder: 0,
  validUntil: null, firstBookingOnly: false, discount: 50, applicable,
  reasonCode: applicable ? null : 'COUPON_MIN_ORDER', reason: applicable ? null : 'This coupon needs a service of at least ₹399',
  ...extra,
});
const settle = () => new Promise((r) => setTimeout(r, 50));

describe('CouponPicker', () => {
  let fixture: ComponentFixture<CouponPicker>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    fixture = TestBed.createComponent(CouponPicker);
    http = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('genieId', 'g1');
    fixture.componentRef.setInput('serviceId', 7);
    fixture.componentRef.setInput('addressId', 'a1');
    fixture.detectChanges();
    await settle();
    http.expectOne((r) => r.url === '/api/v1/coupons' && r.params.get('serviceId') === '7')
      .flush([offer('SAVE50', true), offer('BIG100', false)]);
    fixture.detectChanges();
    await settle();
  });

  afterEach(() => http.verify());

  it('lists public coupons; the ones that cannot be used say why and are disabled', () => {
    const buttons = Array.from(el().querySelectorAll<HTMLButtonElement>('ul button'));
    expect(buttons.map((b) => b.disabled)).toEqual([false, true]);
    expect(buttons[0].textContent).toContain('Save ₹50.00');
    expect(buttons[1].textContent).toContain('at least ₹399');
  });

  it('applies a coupon after the server confirms it, and removes it again', async () => {
    el().querySelector<HTMLButtonElement>('ul button')!.click();
    http.expectOne('/api/v1/coupons/check').flush(offer('SAVE50', true));
    fixture.detectChanges();
    expect(fixture.componentInstance.applied()?.code).toBe('SAVE50');
    expect(el().textContent).toContain('SAVE50 applied');
    el().querySelector<HTMLButtonElement>('button[aria-label="Remove coupon SAVE50"]')!.click();
    fixture.detectChanges();
    expect(fixture.componentInstance.applied()).toBeNull();
  });

  it('shows why a typed code was refused', async () => {
    const input = el().querySelector<HTMLInputElement>('#coupon-code')!;
    input.value = 'nope';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await settle();
    el().querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));
    http.expectOne('/api/v1/coupons/check').flush(
      { code: 'COUPON_NOT_FOUND', detail: "That coupon code doesn't exist", status: 404 }, { status: 404, statusText: 'Not Found' });
    fixture.detectChanges();
    expect(el().querySelector('[role=alert]')?.textContent).toContain("doesn't exist");
    expect(fixture.componentInstance.applied()).toBeNull();
  });
});
