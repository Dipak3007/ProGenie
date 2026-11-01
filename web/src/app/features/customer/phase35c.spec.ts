import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting, TestRequest } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { BookingDetail, BookingItem } from '../../core/api/models';
import { durationText, itemsParam, parseItems } from '../../shared/service-lines';
import { PriceSummary } from '../../shared/ui/booking-parts';
import { ReportDialog } from '../support/report-dialog';
import CheckoutPage from './checkout.page';

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

describe('service lines', () => {
  it('writes and reads the items parameter, dropping anything over the limits', () => {
    expect(itemsParam([{ serviceId: 12, quantity: 2 }, { serviceId: 15, quantity: 1 }])).toBe('12:2,15:1');
    expect(parseItems('12:2, 15')).toEqual([{ serviceId: 12, quantity: 2 }, { serviceId: 15, quantity: 1 }]);
    expect(parseItems('3')).toEqual([{ serviceId: 3, quantity: 1 }]);
    expect(parseItems('1:9,x,2:2,2:1')).toEqual([{ serviceId: 2, quantity: 2 }]);
    expect(parseItems('1,2,3,4,5,6')).toHaveLength(5);
    expect(parseItems(undefined)).toEqual([]);
    expect(durationText(45)).toBe('45 min');
    expect(durationText(120)).toBe('2 h');
    expect(durationText(135)).toBe('2 h 15 min');
  });
});

describe('CheckoutPage with several services', () => {
  let fixture: ComponentFixture<CheckoutPage>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;
  const services = [
    { serviceId: 1, name: 'Fan installation', durationMinutes: 45, price: 269 },
    { serviceId: 2, name: 'Switchboard repair', durationMinutes: 30, price: 219 },
    { serviceId: 5, name: 'House wiring check-up', durationMinutes: 120, price: 659 },
  ];

  /** Answers whatever the page loads (slot days, prices, coupons) and returns the price requests. */
  function answerAll(): TestRequest[] {
    for (const r of http.match((x) => x.url.endsWith('/slot-days'))) r.flush([]);
    for (const r of http.match((x) => x.url === '/api/v1/coupons')) r.flush([]);
    const prices = http.match((x) => x.url === '/api/v1/pricing/estimate');
    for (const r of prices) {
      const lines = parseItems(r.request.params.get('items')).map((l) => {
        const s = services.find((x) => x.serviceId === l.serviceId)!;
        return { serviceId: s.serviceId, serviceName: s.name, quantity: l.quantity, unitPrice: s.price, amount: s.price * l.quantity, durationMinutes: s.durationMinutes, commissionAmount: 0 };
      });
      const amount = lines.reduce((sum, l) => sum + l.amount, 0);
      r.flush({ serviceAmount: amount, distanceKm: 3, distanceMethod: 'ESTIMATE', travelFee: 40, tipAmount: 0, totalAmount: amount + 40, commissionRate: 0.05, commissionAmount: 0, genieEarning: 0, lines, durationMinutes: 0 });
    }
    return prices;
  }

  async function settle(): Promise<TestRequest[]> {
    fixture.detectChanges();
    TestBed.tick();
    await wait(200);
    const prices = answerAll();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return prices;
  }

  const serviceButton = (name: string) =>
    Array.from(el().querySelectorAll<HTMLButtonElement>('[data-testid=checkout-service] > button')).find((b) => b.textContent?.includes(name))!;
  const summary = () =>
    Array.from(el().querySelectorAll('[data-testid=summary-services] > span'))
      .map((x) => x.textContent!.trim())
      .join(' | ');

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    fixture = TestBed.createComponent(CheckoutPage);
    http = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('genieId', 'g1');
    fixture.componentRef.setInput('items', '1:2,99:1');
    fixture.detectChanges();
    await wait(10);
    http.expectOne('/api/v1/genies/g1').flush({
      genie: { id: 'g1', fullName: 'Ravi Patel', online: true, categories: 'Electrician', avgRating: 4.8, ratingCount: 12 },
      services,
      reviews: [],
    });
    for (const r of http.match('/api/v1/me/addresses')) {
      r.flush([{ id: 'a1', label: 'Home', line1: '12 Park Road', area: 'Satellite', lat: 23.03, lng: 72.52, isDefault: true }]);
    }
    await settle();
  });

  it('starts from the services in the link that this Genie offers, with their quantities', async () => {
    expect(summary()).toBe('Fan installation ×2');
    expect(el().querySelector('[data-testid=qty-value-1]')?.textContent?.trim()).toBe('2');
    expect(el().querySelector('[data-testid=checkout-duration]')?.textContent).toContain('About 1 h 30 min');
  });

  it('adds another service to the same visit and prices all of them together', async () => {
    serviceButton('Switchboard repair').click();
    const prices = await settle();
    expect(prices.at(-1)?.request.params.get('items')).toBe('1:2,2:1');
    expect(summary()).toBe('Fan installation ×2 | Switchboard repair');
    expect(el().querySelector('[data-testid=checkout-duration]')?.textContent).toContain('About 2 h');
    expect(el().querySelector('#summary dl')?.textContent).toContain('Switchboard repair');

    // more of one, then removing it again
    (el().querySelector('[aria-label="One more Switchboard repair"]') as HTMLButtonElement).click();
    await settle();
    expect(summary()).toContain('Switchboard repair ×2');
    (el().querySelector('[aria-label="One fewer Switchboard repair"]') as HTMLButtonElement).click();
    (el().querySelector('[aria-label="One fewer Switchboard repair"]') as HTMLButtonElement).click();
    await settle();
    expect(summary()).toBe('Fan installation ×2');
  });

  it('refuses a job longer than one working day', async () => {
    serviceButton('House wiring check-up').click();
    await settle();
    for (let i = 0; i < 4; i++) {
      (el().querySelector('[aria-label="One more House wiring check-up"]') as HTMLButtonElement).click();
      await settle();
    }
    const note = el().querySelector('[data-testid=checkout-duration]')!;
    expect(note.getAttribute('role')).toBe('alert');
    expect(note.textContent).toContain('more than one day');
    expect(el().querySelector('pg-slot-picker')).toBeNull();
  });
});

const item = (id: number, serviceName: string, quantity: number, amount: number): BookingItem => ({
  id, serviceId: id, serviceName, quantity, unitPrice: amount / quantity, amount, durationMinutes: 30, discountAmount: 0,
});

describe('PriceSummary with several services', () => {
  it('lists each service and lets the customer remove one', () => {
    TestBed.configureTestingModule({});
    const fixture = TestBed.createComponent(PriceSummary);
    const booking = {
      serviceName: 'Fan installation ×2 + Switchboard repair', serviceAmount: 757, distanceKm: 3, travelFee: 40, instantFee: 0,
      extraAmount: 0, tipAmount: 0, discountAmount: 0, totalAmount: 797, commissionAmount: 37.85, geniePayout: 759.15,
      items: [item(11, 'Fan installation', 2, 538), item(12, 'Switchboard repair', 1, 219)],
    } as unknown as BookingDetail;
    fixture.componentRef.setInput('booking', booking);
    fixture.componentRef.setInput('removable', true);
    const removed: BookingItem[] = [];
    fixture.componentInstance.removeItem.subscribe((i) => removed.push(i));
    fixture.detectChanges();
    const rows = Array.from((fixture.nativeElement as HTMLElement).querySelectorAll('[data-testid=price-line]'));
    expect(rows.map((r) => r.querySelector('dt')?.textContent?.replace('Remove', '').trim())).toEqual(['Fan installation ×2', 'Switchboard repair']);
    (rows[1].querySelector('button') as HTMLButtonElement).click();
    expect(removed.map((i) => i.id)).toEqual([12]);
  });
});

describe('ReportDialog on a booking with several services', () => {
  it('sends which services the problem is about', async () => {
    HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
      this.setAttribute('open', '');
    };
    HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
      this.removeAttribute('open');
    };
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    const fixture = TestBed.createComponent(ReportDialog);
    const http = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('open', true);
    fixture.componentRef.setInput('bookingId', 'b-1');
    fixture.componentRef.setInput('items', [item(11, 'Fan installation', 2, 538), item(12, 'Switchboard repair', 1, 219)]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const boxes = el.querySelectorAll<HTMLInputElement>('[data-testid=report-items] input[type=checkbox]');
    expect(boxes).toHaveLength(2);
    boxes[1].click();
    (el.querySelector('input[type=radio][value=QUALITY]') as HTMLInputElement).click();
    fixture.detectChanges();
    for (const r of http.match((x) => x.url === '/api/v1/help/suggestions')) r.flush([]);
    const subject = el.querySelector('#rp-subject') as HTMLInputElement;
    subject.value = 'Switch sparks';
    subject.dispatchEvent(new Event('input'));
    const desc = el.querySelector('#rp-desc') as HTMLTextAreaElement;
    desc.value = 'The new switch sparks when used.';
    desc.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    (el.querySelector('form') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const req = http.expectOne('/api/v1/bookings/b-1/tickets');
    expect(req.request.body.itemIds).toEqual([12]);
  });
});
