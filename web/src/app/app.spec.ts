import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { App } from './app';

/** The header and footer load the live cities; answer that request so the app can become stable. */
function flushCities(): void {
  TestBed.tick();
  TestBed.inject(HttpTestingController)
    .match('/api/v1/cities')
    .forEach((r) => r.flush([{ id: 1, slug: 'ahmedabad', name: 'Ahmedabad', state: 'Gujarat', lat: 23.02, lng: 72.57, radiusKm: 35 }]));
}

describe('App shell', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('renders the header with the ProGenie wordmark', async () => {
    const fixture = TestBed.createComponent(App);
    flushCities();
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('header')?.textContent).toContain('Genie');
    expect(el.querySelector('footer')?.textContent).toContain('Ahmedabad, Gujarat');
  });

  it('offers a skip link for keyboard users', async () => {
    const fixture = TestBed.createComponent(App);
    flushCities();
    await fixture.whenStable();
    const skip = (fixture.nativeElement as HTMLElement).querySelector('a[href="#main"]');
    expect(skip?.textContent).toContain('Skip to content');
  });
});
