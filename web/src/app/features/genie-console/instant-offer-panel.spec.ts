import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter, Router } from '@angular/router';
import { signal } from '@angular/core';
import { Subject } from 'rxjs';

import { InstantOffer } from '../../core/api/models';
import { LiveStream } from '../../core/live/live-stream';
import { GenieStore } from './genie-store';
import { InstantOfferPanel } from './instant-offer-panel';

const offer = (id: number, secondsLeft = 40): InstantOffer => ({
  id, requestId: 'r' + id, serviceName: 'Fan installation', durationMinutes: 45, area: 'Bodakdev', city: 'Ahmedabad',
  distanceKm: 3.2, earning: 412.5, paymentMethod: 'CASH', expiresAt: new Date(Date.now() + secondsLeft * 1000).toISOString(), notes: null,
});

describe('InstantOfferPanel', () => {
  let fixture: ComponentFixture<InstantOfferPanel>;
  let http: HttpTestingController;
  const streams: Record<string, Subject<unknown>> = {};
  const connected = signal(true);
  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(() => {
    ['instant_offer', 'presence'].forEach((k) => (streams[k] = new Subject()));
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
        { provide: LiveStream, useValue: { on: (t: string) => streams[t], connected } },
      ],
    });
    const store = TestBed.inject(GenieStore);
    store.set({ verificationStatus: 'APPROVED', online: true } as never);
    fixture = TestBed.createComponent(InstantOfferPanel);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    TestBed.tick();
    http.expectOne('/api/v1/genie/instant-offers').flush([]);
    fixture.detectChanges();
  });
  afterEach(() => http.verify());

  it('shows a live offer with the earning and a countdown, and removes it when it is withdrawn', () => {
    expect(el().querySelector('[data-testid=instant-offer]')).toBeNull();
    streams['instant_offer'].next(offer(7));
    fixture.detectChanges();
    const card = el().querySelector('[data-testid=instant-offer]')!;
    expect(card.textContent).toContain('Fan installation');
    expect(card.textContent).toContain('412.50');
    expect(card.textContent).toMatch(/\d+s/);
    streams['instant_offer'].next({ offerId: 7, change: 'WITHDRAWN' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=instant-offer]')).toBeNull();
  });

  it('accepts and opens the job; a late accept says the job is gone', async () => {
    const router = TestBed.inject(Router);
    const go = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    streams['instant_offer'].next(offer(8));
    fixture.detectChanges();
    (el().querySelectorAll<HTMLButtonElement>('[data-testid=instant-offer] button')[1]).click();
    http.expectOne('/api/v1/genie/instant-offers/8/accept').flush({ id: 'b-1' });
    http.expectOne((r) => r.url === '/api/v1/genie/bookings').flush({ total: 0, items: [] });
    expect(go).toHaveBeenCalledWith(['/genie/bookings', 'b-1']);

    streams['instant_offer'].next(offer(9));
    fixture.detectChanges();
    (el().querySelectorAll<HTMLButtonElement>('[data-testid=instant-offer] button')[1]).click();
    http.expectOne('/api/v1/genie/instant-offers/9/accept').flush(
      { code: 'OFFER_GONE', detail: 'This job is no longer available', status: 409 }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=instant-offer]')).toBeNull();
  });

  it('passing on an offer tells the server and hides it; an expired offer is not shown', () => {
    streams['instant_offer'].next(offer(10, -1));
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=instant-offer]')).toBeNull();
    streams['instant_offer'].next(offer(11));
    fixture.detectChanges();
    el().querySelector<HTMLButtonElement>('[data-testid=instant-offer] button')!.click();
    http.expectOne('/api/v1/genie/instant-offers/11/decline').flush(null);
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=instant-offer]')).toBeNull();
  });

  it('marks the Genie offline when the server switched them off', () => {
    streams['presence'].next({ online: false });
    expect(TestBed.inject(GenieStore).profile()?.online).toBe(false);
  });
});
