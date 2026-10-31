import { Component, input, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Subject } from 'rxjs';

import { Tracking } from '../../core/api/models';
import { LiveStream } from '../../core/live/live-stream';
import { TrackingMap } from '../../shared/ui/tracking-map';
import { LiveTracking, POLL_MS } from './live-tracking';

@Component({ selector: 'pg-tracking-map', template: '' })
class StubMap {
  readonly genie = input<{ lat: number; lng: number } | null>(null);
  readonly home = input<{ lat: number; lng: number } | null>(null);
  readonly label = input('');
}

const tracking = (extra: Partial<Tracking> = {}): Tracking => ({
  bookingId: 'b1', status: 'ON_THE_WAY', active: true, travelStartedAt: '2026-10-31T10:00:00+05:30',
  lat: 23.05, lng: 72.555, accuracyM: 8, distanceKm: 3.4, distanceMethod: 'ROAD', etaMinutes: 10,
  updatedAt: new Date().toISOString(), customerLat: 23.03, customerLng: 72.56, ...extra,
});

describe('LiveTracking', () => {
  let fixture: ComponentFixture<LiveTracking>;
  let http: HttpTestingController;
  let events: Subject<Tracking>;
  const connected = signal(true);
  const el = () => fixture.nativeElement as HTMLElement;
  const map = () => fixture.debugElement.children[0]?.query((d) => d.componentInstance instanceof StubMap)?.componentInstance as StubMap;

  beforeEach(() => {
    events = new Subject<Tracking>();
    connected.set(true);
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), { provide: LiveStream, useValue: { on: () => events, connected } }],
    });
    TestBed.overrideComponent(LiveTracking, { remove: { imports: [TrackingMap] }, add: { imports: [StubMap] } });
    fixture = TestBed.createComponent(LiveTracking);
    http = TestBed.inject(HttpTestingController);
    fixture.componentRef.setInput('bookingId', 'b1');
    fixture.componentRef.setInput('genieName', 'Ravi Patel');
    fixture.detectChanges();
  });
  afterEach(() => http.verify());

  it('waits for the first position, then shows the arrival estimate and both points on the map', () => {
    http.expectOne('/api/v1/bookings/b1/tracking').flush(tracking({ lat: null, lng: null, distanceKm: null, etaMinutes: null, updatedAt: null }));
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=waiting]')?.textContent).toContain('Ravi set off');

    events.next(tracking());
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=eta]')?.textContent).toContain('About 10 min away');
    expect(el().textContent).toContain('3.4 km by road');
    expect(el().querySelector('[data-testid=updated]')?.textContent).toContain('just now');
    expect(map().genie()).toEqual({ lat: 23.05, lng: 72.555 });
    expect(map().home()).toEqual({ lat: 23.03, lng: 72.56 });
  });

  it('ignores other bookings and says "last seen" when the Genie stops sending', () => {
    http.expectOne('/api/v1/bookings/b1/tracking').flush(tracking({ updatedAt: new Date(Date.now() - 6 * 60_000).toISOString() }));
    events.next(tracking({ bookingId: 'other', etaMinutes: 99 }));
    fixture.detectChanges();
    expect(el().textContent).not.toContain('99 min');

    events.next(tracking({ updatedAt: new Date(Date.now() - 5 * 60_000).toISOString(), distanceMethod: 'ESTIMATE' }));
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=last-seen]')?.textContent).toContain('Last seen 5 min ago');
    expect(el().textContent).toContain('(approx.)');
  });

  it('keeps a newer live position when an older answer arrives late', () => {
    const req = http.expectOne('/api/v1/bookings/b1/tracking');
    events.next(tracking({ etaMinutes: 7 }));
    req.flush(tracking({ lat: null, lng: null, etaMinutes: null, updatedAt: null }));
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=eta]')?.textContent).toContain('About 7 min away');
  });

  it('says when the position is only approximate (a device without GPS)', () => {
    http.expectOne('/api/v1/bookings/b1/tracking').flush(tracking({ accuracyM: 150000 }));
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=rough-location]')?.textContent).toContain('within about');
    expect(el().querySelector('[data-testid=rough-location]')?.textContent).toContain('150');
  });

  it('polls while the live stream is down and reports the end of the trip', () => {
    vi.useFakeTimers();
    try {
      const f = TestBed.createComponent(LiveTracking);
      let ended = 0;
      f.componentInstance.ended.subscribe(() => ended++);
      f.componentRef.setInput('bookingId', 'b1');
      f.detectChanges();
      http.match('/api/v1/bookings/b1/tracking').forEach((r) => r.flush(tracking()));
      connected.set(false);
      vi.advanceTimersByTime(POLL_MS);
      http.expectOne('/api/v1/bookings/b1/tracking').flush(tracking({ status: 'IN_PROGRESS', active: false, lat: null, lng: null }));
      expect(ended).toBe(1);
      f.destroy();
    } finally {
      vi.useRealTimers();
    }
  });
});
