import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { BookingDetail, Quote } from '../../core/api/models';
import { QuoteSummary } from '../../shared/ui/quote-summary';
import { QuoteEditor } from '../genie-console/quote-editor';
import { QuotePanel } from './quote-panel';

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

function dialogs(): void {
  HTMLDialogElement.prototype.showModal ??= function (this: HTMLDialogElement) {
    this.setAttribute('open', '');
  };
  HTMLDialogElement.prototype.close ??= function (this: HTMLDialogElement) {
    this.removeAttribute('open');
  };
}

const quote = (extra: Partial<Quote> = {}): Quote => ({
  id: 'q1', inspectionBookingId: 'b1', inspectionBookingRef: 'PG-2026-000101', status: 'SENT', version: 2,
  lines: [
    { id: 1, serviceId: 1, description: 'Fan installation', quantity: 2, unitPrice: 269, amount: 538 },
    { id: 2, serviceId: null, description: 'Replace 20 m of old wiring', quantity: 1, unitPrice: 4500, amount: 4500 },
  ],
  totalAmount: 5038, inspectionCredit: 199, jobAmount: 4839, durationMinutes: 180, note: 'Materials included',
  validUntil: '2026-11-08T10:00:00Z', declineReason: null, respondedAt: null, jobBookingId: null, jobBookingRef: null,
  createdAt: '2026-11-01T10:00:00Z', updatedAt: '2026-11-01T11:00:00Z', ...extra,
});

const inspection = (extra: Partial<BookingDetail> = {}): BookingDetail =>
  ({
    id: 'b1', bookingRef: 'PG-2026-000101', status: 'COMPLETED', kind: 'INSPECTION', serviceId: 5, serviceAmount: 199,
    genie: { id: 'g1', name: 'Ravi Patel' }, allowedActions: [], items: [
      { id: 7, serviceId: 5, serviceName: 'House wiring check-up', quantity: 1, unitPrice: 0, amount: 0, durationMinutes: 90, discountAmount: 0 },
      { id: 8, serviceId: null, serviceName: 'Inspection visit', quantity: 1, unitPrice: 199, amount: 199, durationMinutes: 30, discountAmount: 0 },
    ],
    inspectionCredit: 0, quote: null, ...extra,
  }) as unknown as BookingDetail;

describe('QuoteSummary', () => {
  it('lists the lines, takes off the inspection fee and marks custom lines', () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(QuoteSummary);
    fixture.componentRef.setInput('quote', quote());
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent!;
    expect(text).toContain('Fan installation ×2');
    expect(text).toContain('Replace 20 m of old wiring');
    expect(text).toContain('(custom)');
    expect(text).toContain('Inspection fee already paid');
    expect((fixture.nativeElement as HTMLElement).querySelector('[data-testid=quote-job-amount]')?.textContent).toContain('4,839');
    expect(text).toContain('about 3 h');
  });
});

describe('QuotePanel', () => {
  beforeEach(() => {
    dialogs();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
  });

  it('says a quote is coming after the visit', () => {
    const fixture = TestBed.createComponent(QuotePanel);
    fixture.componentRef.setInput('booking', inspection());
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('will send you a priced quote within 7 days');
  });

  it('books the quoted job at the picked time', async () => {
    const fixture = TestBed.createComponent(QuotePanel);
    const http = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('booking', inspection({ quote: quote(), allowedActions: ['ACCEPT_QUOTE', 'DECLINE_QUOTE'] }));
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Accept and pick a time'))!.click();
    fixture.detectChanges();
    await wait(10);
    const days = http.expectOne((r) => r.url.endsWith('/genies/g1/slot-days') && r.params.get('quoteId') === 'q1');
    days.flush([{ date: '2026-11-03', available: 2 }]);
    fixture.detectChanges();
    http.expectOne((r) => r.url.endsWith('/genies/g1/slots') && r.params.get('quoteId') === 'q1')
      .flush({ date: '2026-11-03', genieId: 'g1', serviceId: 0, durationMinutes: 180, slots: [{ start: '2026-11-03T05:30:00Z', end: '2026-11-03T08:30:00Z' }] });
    fixture.detectChanges();
    (el.querySelector('pg-slot-picker button[aria-pressed]') as HTMLButtonElement).click();
    fixture.detectChanges();
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Book the job'))!.click();
    const req = http.expectOne('/api/v1/quotes/q1/accept');
    expect(req.request.body).toEqual({ slotStart: '2026-11-03T05:30:00Z', paymentMethod: 'CASH', tipAmount: 0, notes: null });
  });
});

describe('QuoteEditor', () => {
  it('starts from the services the customer asked about and sends lines, length and note', async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    const fixture = TestBed.createComponent(QuoteEditor);
    const http = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('booking', inspection({ allowedActions: ['SEND_QUOTE'] }));
    fixture.detectChanges();
    await wait(10);
    http.expectOne('/api/v1/genie/profile').flush({
      services: [
        { serviceId: 1, name: 'Fan installation', price: 269, durationMinutes: 45 },
        { serviceId: 5, name: 'House wiring check-up', price: 659, durationMinutes: 90 },
      ],
    });
    await fixture.whenStable();
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('[data-testid=quote-line]')).toHaveLength(1);
    expect(el.querySelector('[data-testid=quote-total]')?.textContent).toContain('659');

    // add a custom line: it needs a description before it can be sent
    Array.from(el.querySelectorAll('button')).find((b) => b.textContent?.includes('Add a line'))!.click();
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    const send = () => Array.from(el.querySelectorAll('button[type=submit]'))[0] as HTMLButtonElement;
    expect(send().disabled).toBe(true);
    const lines = el.querySelectorAll('[data-testid=quote-line]');
    const desc = lines[1].querySelector('input[maxlength="150"]') as HTMLInputElement;
    desc.value = 'Replace 20 m of old wiring';
    desc.dispatchEvent(new Event('input'));
    const price = lines[1].querySelectorAll('input[type=number]')[1] as HTMLInputElement;
    price.value = '4500';
    price.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=quote-total]')?.textContent).toContain('5,159');
    expect(send().disabled).toBe(false);
    send().click();
    const req = http.expectOne('/api/v1/genie/bookings/b1/quote');
    expect(req.request.body.lines).toEqual([
      { serviceId: 5, description: null, quantity: 1, unitPrice: 659 },
      { serviceId: null, description: 'Replace 20 m of old wiring', quantity: 1, unitPrice: 4500 },
    ]);
    expect(req.request.body.durationMinutes).toBe(90);
  });
});
