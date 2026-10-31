import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { signal } from '@angular/core';
import { Subject } from 'rxjs';

import { InstantQuote, InstantRequest } from '../../core/api/models';
import { LiveStream } from '../../core/live/live-stream';
import GenieNowPage from './genie-now.page';

const quote = (extra: Partial<InstantQuote> = {}): InstantQuote => ({
  available: true, reasonCode: null, reason: null, serviceId: 3, serviceName: 'Fan installation', durationMinutes: 45,
  genieCount: 2, priceFrom: 349, priceTo: 420, instantFee: 25, arrivalMinutes: 60, offerSeconds: 45, maxGenies: 5, ...extra,
});
const request = (status: InstantRequest['status'], extra: Partial<InstantRequest> = {}): InstantRequest => ({
  id: 'q1', status, serviceId: 3, serviceName: 'Fan installation', createdAt: new Date().toISOString(), endedAt: null,
  geniesAsked: 1, maxGenies: 5, offerExpiresAt: new Date(Date.now() + 30_000).toISOString(), bookingId: null,
  message: status === 'NO_GENIE' ? 'No Genie is free right now. Book a time slot instead.' : 'Asking Genie 1 of 5…', ...extra,
});
const settle = () => new Promise((r) => setTimeout(r, 30));

describe('GenieNowPage', () => {
  let fixture: ComponentFixture<GenieNowPage>;
  let http: HttpTestingController;
  let events: Subject<InstantRequest>;
  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(async () => {
    events = new Subject();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
        { provide: LiveStream, useValue: { on: () => events, connected: signal(true) } },
      ],
    });
    fixture = TestBed.createComponent(GenieNowPage);
    http = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('serviceId', '3');
    fixture.detectChanges();
    await settle();
    http.expectOne('/api/v1/me/addresses').flush([
      { id: 'a1', label: 'Home', line1: '1 Main St', line2: null, landmark: null, area: 'Bodakdev', cityId: 1, cityName: 'Ahmedabad',
        pincode: '380054', lat: 23.03, lng: 72.5, isDefault: true },
    ]);
    fixture.detectChanges();
    await settle();
    fixture.detectChanges();
  });
  afterEach(() => http.verify());

  function flushQuote(q: InstantQuote): void {
    http.expectOne((r) => r.url === '/api/v1/instant/quote' && r.params.get('addressId') === 'a1').flush(q);
    fixture.detectChanges();
  }

  it('shows the price range for the default address and follows the search until a Genie accepts', async () => {
    flushQuote(quote());
    await settle();
    http.match((r) => r.url === '/api/v1/coupons').forEach((r) => r.flush([]));
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=instant-quote]')?.textContent).toContain('2 Genies free near you');
    expect(el().textContent).toContain('₹25 Genie Now fee');

    const go = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const button = Array.from(el().querySelectorAll('button')).find((b) => b.textContent?.includes('Find a Genie now'))!;
    button.click();
    const req = http.expectOne('/api/v1/instant/requests');
    expect(req.request.body).toMatchObject({ serviceId: 3, addressId: 'a1', paymentMethod: 'CASH', tipAmount: 0 });
    req.flush(request('SEARCHING'));
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=instant-message]')?.textContent).toContain('Asking Genie 1 of 5');

    events.next(request('BOOKED', { bookingId: 'b9' }));
    fixture.detectChanges();
    expect(go).toHaveBeenCalledWith(['/account/bookings', 'b9']);
  });

  it('offers a time slot when nobody is free, and says why the service is unavailable', async () => {
    flushQuote(quote({ available: false, reasonCode: 'NO_GENIE_NEARBY', reason: 'No Genie is free near you right now. Book a time slot instead.', genieCount: 0, priceFrom: null, priceTo: null }));
    expect(el().querySelector('[data-testid=instant-quote]')?.textContent).toContain('No Genie is free near you');
    expect(Array.from(el().querySelectorAll('button')).some((b) => b.textContent?.includes('Find a Genie now'))).toBe(false);

    fixture.componentInstance.search.set(request('NO_GENIE', { offerExpiresAt: null }));
    fixture.detectChanges();
    expect(el().textContent).toContain('No Genie is free right now');
    expect(el().querySelector('a[href="/services"]')).not.toBeNull();
  });
});
