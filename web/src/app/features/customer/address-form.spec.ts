import { Component, input, output } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { By } from '@angular/platform-browser';

import { AddressForm } from './address-form';
import { LatLng, MapPicker } from '../../shared/ui/map-picker';

/** Stands in for the Leaflet map: shows the pin it was given and lets the test "drag" it. */
@Component({ selector: 'pg-map-picker', template: '' })
class FakeMap {
  readonly lat = input<number | null>(null);
  readonly lng = input<number | null>(null);
  readonly center = input<LatLng | null>(null);
  readonly moved = output<LatLng>();
}

const served = (pincode: string, area: string) => ({
  pincode, serviceable: true, cityId: 1, citySlug: 'ahmedabad', cityName: 'Ahmedabad', cityState: 'Gujarat', area, lat: 23.03, lng: 72.56,
});
const settle = () => new Promise((r) => setTimeout(r, 400));

describe('AddressForm: PIN code and map pin stay in step', () => {
  let fixture: ComponentFixture<AddressForm>;
  let http: HttpTestingController;

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    TestBed.overrideComponent(AddressForm, { remove: { imports: [MapPicker] }, add: { imports: [FakeMap] } });
    fixture = TestBed.createComponent(AddressForm);
    http = TestBed.inject(HttpTestingController);
    fixture.detectChanges();
    flushCities();
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  function flushCities(): void {
    for (const r of http.match('/api/v1/cities')) {
      r.flush([{ id: 1, slug: 'ahmedabad', name: 'Ahmedabad', state: 'Gujarat', lat: 23.02, lng: 72.57, radiusKm: 35 }]);
    }
  }

  const map = () => fixture.debugElement.query(By.directive(FakeMap)).componentInstance as FakeMap;
  const pinInput = () => fixture.nativeElement.querySelector('#a-pin') as HTMLInputElement;
  const note = () => (fixture.nativeElement.querySelector('[data-testid="pin-sync"]') as HTMLElement).textContent?.trim() ?? '';

  async function type(pincode: string): Promise<void> {
    const el = pinInput();
    el.value = pincode;
    el.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
  }

  async function render(): Promise<void> {
    fixture.detectChanges();
    flushCities();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('moves the pin to a typed PIN code', async () => {
    await type('380009');
    http.expectOne((r) => r.url === '/api/v1/serviceability' && r.params.get('pincode') === '380009').flush(served('380009', 'Navrangpura'));
    http.expectOne('/api/v1/geo/pincodes/380009').flush({ pincode: '380009', found: true, lat: 23.035, lng: 72.561, area: 'Navrangpura', source: 'SERVED' });
    await render();
    expect(map().lat()).toBe(23.035);
    expect(map().lng()).toBe(72.561);
    expect(note()).toContain('Pin moved to 380009');
  });

  it('says so when a PIN code cannot be placed, and leaves the pin alone', async () => {
    await type('110001');
    http.expectOne((r) => r.url === '/api/v1/serviceability').flush({ ...served('110001', ''), serviceable: false, cityId: null, area: null, lat: null, lng: null });
    http.expectOne('/api/v1/geo/pincodes/110001').flush({ pincode: '110001', found: false, lat: null, lng: null, area: null, source: null });
    await render();
    expect(map().lat()).toBeNull();
    expect(note()).toContain("couldn't find 110001");
  });

  it('fills in the PIN code where the pin lands, without moving the pin again', async () => {
    map().moved.emit({ lat: 23.029, lng: 72.519 });
    await render();
    expect(note()).toContain('Finding the PIN code here');
    await settle();
    http.expectOne((r) => r.url === '/api/v1/geo/pincode-at' && r.params.get('lat') === '23.029')
      .flush({ lat: 23.029, lng: 72.519, inIndia: true, pincode: '380015', area: 'Satellite', source: 'GEOCODER' });
    http.expectOne((r) => r.url === '/api/v1/serviceability' && r.params.get('pincode') === '380015').flush(served('380015', 'Satellite, Vastrapur'));
    http.expectNone('/api/v1/geo/pincodes/380015');
    await render();
    expect(pinInput().value).toBe('380015');
    expect((fixture.nativeElement.querySelector('#a-area') as HTMLInputElement).value).toBe('Satellite');
    expect(map().lat()).toBe(23.029);
    expect(note()).toContain('PIN code 380015 filled in from the map');

    // typing the PIN code the pin is already in does not move it
    await type('380015');
    http.expectOne((r) => r.url === '/api/v1/serviceability').flush(served('380015', 'Satellite, Vastrapur'));
    http.expectNone('/api/v1/geo/pincodes/380015');
  });

  it('asks only once the pin settles, and typing wins over an answer still on its way', async () => {
    map().moved.emit({ lat: 23.01, lng: 72.51 });
    map().moved.emit({ lat: 23.02, lng: 72.52 });
    await settle();
    const lookup = http.expectOne('/api/v1/geo/pincode-at?lat=23.02&lng=72.52');
    await type('380054');
    lookup.flush({ lat: 23.02, lng: 72.52, inIndia: true, pincode: '380015', area: null, source: 'NEAREST' });
    http.expectOne((r) => r.url === '/api/v1/serviceability').flush(served('380054', 'Bodakdev'));
    http.expectOne('/api/v1/geo/pincodes/380054').flush({ pincode: '380054', found: true, lat: 23.04, lng: 72.505, area: 'Bodakdev', source: 'SERVED' });
    await render();
    expect(pinInput().value).toBe('380054');
    expect(map().lat()).toBe(23.04);
  });
});
