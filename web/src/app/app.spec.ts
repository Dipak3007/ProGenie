import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { App } from './app';

describe('App shell', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('renders the header with the ProGenie wordmark', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('header')?.textContent).toContain('Genie');
  });

  it('offers a skip link for keyboard users', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const skip = (fixture.nativeElement as HTMLElement).querySelector('a[href="#main"]');
    expect(skip?.textContent).toContain('Skip to content');
  });
});
