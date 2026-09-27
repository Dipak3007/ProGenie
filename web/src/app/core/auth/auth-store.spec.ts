import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { AuthStore } from './auth-store';

describe('AuthStore', () => {
  let store: AuthStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(AuthStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('keeps the access token in memory after login', () => {
    store.login({ identifier: 'customer@example.com', password: 'x' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush({
      accessToken: 'abc',
      expiresIn: 900,
      user: { id: '1', fullName: 'Demo Customer', email: 'customer@example.com', phone: '9825000005', role: 'CUSTOMER' },
    });

    expect(store.isLoggedIn()).toBe(true);
    expect(store.token()).toBe('abc');
    expect(store.firstName()).toBe('Demo');
  });

  it('routes each role to its home', () => {
    expect(store.homeFor('GENIE')).toBe('/genie');
    expect(store.homeFor('ADMIN')).toBe('/admin');
    expect(store.homeFor('CUSTOMER')).toBe('/');
  });
});
