import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { City } from '../api/models';
import { CityStore, joinNames } from './city-store';

const AHM: City = { id: 1, slug: 'ahmedabad', name: 'Ahmedabad', state: 'Gujarat', lat: 23.02, lng: 72.57, radiusKm: 35 };
const VAD: City = { id: 2, slug: 'vadodara', name: 'Vadodara', state: 'Gujarat', lat: 22.31, lng: 73.18, radiusKm: 25 };

describe('CityStore', () => {
  beforeEach(() => {
    try {
      localStorage.removeItem('pg.city');
    } catch {
      // ignore
    }
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  });

  async function loaded(cities: City[]): Promise<CityStore> {
    const store = TestBed.inject(CityStore);
    store.cities(); // reading starts the request
    TestBed.tick();
    TestBed.inject(HttpTestingController).expectOne('/api/v1/cities').flush(cities);
    await Promise.resolve();
    TestBed.tick();
    return store;
  }

  it('defaults to the first live city and remembers a choice', async () => {
    const store = await loaded([AHM, VAD]);
    expect(store.city()?.slug).toBe('ahmedabad');
    expect(store.multiCity()).toBe(true);
    store.select('vadodara');
    expect(store.city()?.name).toBe('Vadodara');
    expect(localStorage.getItem('pg.city')).toBe('vadodara');
  });

  it('switches city when a served PIN code belongs to another city', async () => {
    const store = await loaded([AHM, VAD]);
    store.checkPincode('390007').subscribe();
    TestBed.inject(HttpTestingController)
      .expectOne((r) => r.url === '/api/v1/serviceability' && r.params.get('pincode') === '390007')
      .flush({ pincode: '390007', serviceable: true, cityId: 2, citySlug: 'vadodara', cityName: 'Vadodara', cityState: 'Gujarat', area: 'Alkapuri', lat: null, lng: null });
    expect(store.slug()).toBe('vadodara');
  });

  it('joins city names for sentences', () => {
    expect(joinNames([])).toBe('');
    expect(joinNames(['Ahmedabad'])).toBe('Ahmedabad');
    expect(joinNames(['Ahmedabad', 'Surat', 'Vadodara'])).toBe('Ahmedabad, Surat and Vadodara');
  });
});
