import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { DemandMap } from '../../core/api/models';
import DemandPage from './demand.page';

const map = (extra: Partial<DemandMap> = {}): DemandMap => ({
  layer: 'BOOKINGS', days: 30, from: '2026-10-01T00:00:00Z', total: 12, unplaced: 1,
  cells: [
    { key: '380015', pincode: '380015', area: 'Satellite', lat: 23.03, lng: 72.52, count: 8, kinds: {} },
    { key: '400001', pincode: '400001', area: null, lat: null, lng: null, count: 3, kinds: { PINCODE_UNSERVED: 3 } },
  ],
  byKind: { BOOKING: 12 }, byReason: {}, byHour: Array(24).fill(0), byWeekday: Array(7).fill(0), ...extra,
});
const settle = () => new Promise((r) => setTimeout(r, 30));

describe('DemandPage', () => {
  let fixture: ComponentFixture<DemandPage>;
  let http: HttpTestingController;
  const el = () => fixture.nativeElement as HTMLElement;

  beforeEach(async () => {
    // the map itself (Leaflet + heat layer on a canvas) is not drawn in jsdom
    vi.spyOn(DemandPage.prototype as unknown as { init: () => Promise<void> }, 'init').mockResolvedValue();
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    fixture = TestBed.createComponent(DemandPage);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    await settle();
    http.expectOne('/api/v1/admin/cities').flush([]);
    http.expectOne('/api/v1/admin/categories').flush([]);
  });
  afterEach(() => http.verify());

  it('loads bookings for the last 30 days and lists the top PIN codes, flagging those not on the map', () => {
    http.expectOne((r) => r.url === '/api/v1/admin/demand' && r.params.get('layer') === 'BOOKINGS' && r.params.get('days') === '30').flush(map());
    fixture.detectChanges();
    expect(el().querySelector('[data-testid=demand-total]')?.textContent).toContain('12 bookings');
    expect(el().querySelector('[data-testid=demand-total]')?.textContent).toContain('1 without a place');
    const top = el().querySelector('[data-testid=demand-top]')!.textContent!;
    expect(top).toContain('380015');
    expect(top).toContain('Satellite');
    expect(top).toContain('Not on the map');
  });

  it('switches to unmet demand with the filters, and explains kinds and reasons', async () => {
    http.expectOne((r) => r.url === '/api/v1/admin/demand').flush(map());
    fixture.componentInstance.layer.set('UNMET');
    fixture.componentInstance.hour.set(19);
    fixture.componentInstance.weekday.set(6);
    fixture.detectChanges();
    await settle();
    const req = http.expectOne((r) => r.url === '/api/v1/admin/demand' && r.params.get('layer') === 'UNMET');
    expect(req.request.params.get('hour')).toBe('19');
    expect(req.request.params.get('weekday')).toBe('6');
    req.flush(map({ layer: 'UNMET', total: 5, byKind: { PINCODE_UNSERVED: 3, INSTANT_NONE_FREE: 2 }, byReason: { OUTSIDE_HOURS: 2 } }));
    fixture.detectChanges();
    expect(el().textContent).toContain('PIN code we');
    expect(el().textContent).toContain('Genie Now: nobody free');
    expect(el().textContent).toContain('Outside working hours');
  });
});
