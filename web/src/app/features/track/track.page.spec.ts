import { Component, input } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import TrackPage from './track.page';
import { TrackingMap } from '../../shared/ui/tracking-map';

/** Stands in for the Leaflet map. */
@Component({ selector: 'pg-tracking-map', template: '' })
class FakeMap {
  readonly genie = input<unknown>(null);
  readonly home = input<unknown>(null);
  readonly label = input('');
}

const onTheWay = {
  status: 'ON_THE_WAY', genieName: 'Ravi', serviceName: 'Fan installation', active: true, lat: 23.03, lng: 72.52,
  accuracyM: 12, distanceKm: 2.4, etaMinutes: 7, updatedAt: new Date().toISOString(), homeLat: 23.02, homeLng: 72.51,
};

describe('TrackPage (Phase 3.5A: the person at the address follows the Genie)', () => {
  let fixture: ComponentFixture<TrackPage>;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.overrideComponent(TrackPage, { remove: { imports: [TrackingMap] }, add: { imports: [FakeMap] } });
    fixture = TestBed.createComponent(TrackPage);
    fixture.componentRef.setInput('token', 'abc123token-abc123token-abc123token-abc1234');
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function answer(body: object, status = 200): Promise<void> {
    await Promise.resolve(); // the first load is queued as a microtask
    const req = http.expectOne('/api/v1/track/abc123token-abc123token-abc123token-abc1234');
    if (status === 200) req.flush(body);
    else req.flush(body, { status, statusText: 'Not Found' });
    fixture.detectChanges();
  }

  const text = () => (fixture.nativeElement as HTMLElement).textContent ?? '';

  it('shows the Genie on the way with the arrival estimate and when to share the code', async () => {
    fixture.detectChanges();
    await answer(onTheWay);
    expect(text()).toContain('Ravi is on the way');
    expect(text()).toContain('About 7 min away');
    expect(text()).toContain('start code');
  });

  it('says the job has started once the Genie is working', async () => {
    fixture.detectChanges();
    await answer({ ...onTheWay, status: 'IN_PROGRESS', active: false, lat: null, lng: null });
    expect(text()).toContain('Ravi has started the job');
  });

  it('says the link has expired after the job', async () => {
    fixture.detectChanges();
    await answer({ code: 'LINK_EXPIRED', title: 'This tracking link has expired' }, 404);
    expect(text()).toContain('This tracking link has expired');
  });
});
