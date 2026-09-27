import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { AuthStore } from '../auth/auth-store';
import { Header } from './header';

describe('Header', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [Header],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
  });

  function links(el: HTMLElement): string[] {
    return Array.from(el.querySelectorAll('nav[aria-label="Main"] a')).map((a) => a.textContent!.trim());
  }

  it('shows the public links to guests', async () => {
    const fixture = TestBed.createComponent(Header);
    await fixture.whenStable();
    expect(links(fixture.nativeElement)).toEqual(['Home', 'Services', 'Become a Genie']);
  });

  it('adapts the links to the role and shows the unread count', async () => {
    const auth = TestBed.inject(AuthStore);
    const http = TestBed.inject(HttpTestingController);
    auth.login({ identifier: 'genie@example.com', password: 'x' }).subscribe();
    http.expectOne('/api/v1/auth/login').flush({
      accessToken: 't',
      expiresIn: 900,
      user: { id: 'g1', fullName: 'Ravi Patel', email: null, phone: '9825000001', role: 'GENIE' },
    });

    const fixture = TestBed.createComponent(Header);
    await fixture.whenStable();
    http.expectOne('/api/v1/notifications/unread-count').flush({ unread: 3 });
    await fixture.whenStable();

    const el = fixture.nativeElement as HTMLElement;
    expect(links(el)).toEqual(['Dashboard', 'Jobs', 'Earnings']);
    expect(el.querySelector('button[aria-label="3 unread notifications"]')).not.toBeNull();
  });
});
