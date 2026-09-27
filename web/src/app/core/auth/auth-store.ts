import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, finalize, firstValueFrom, map, shareReplay } from 'rxjs';

import { API } from '../api/api';
import { AuthResponse, LegalKind, LoginRequest, RegisterRequest, Role, User } from '../api/models';

/**
 * Holds the logged-in user as signals.
 *
 * Security model: the short-lived access token lives only in memory (never localStorage), so an XSS
 * bug cannot steal a long-lived credential. The refresh token is an HttpOnly cookie set by the API;
 * on page reload we call /auth/refresh to get a new access token.
 */
@Injectable({ providedIn: 'root' })
export class AuthStore {
  private readonly http = inject(HttpClient);
  private readonly router = inject(Router);

  private readonly _user = signal<User | null>(null);
  private accessToken: string | null = null;
  private refreshInFlight: Observable<string> | null = null;

  readonly user = this._user.asReadonly();
  readonly isLoggedIn = computed(() => this._user() !== null);
  readonly role = computed<Role | null>(() => this._user()?.role ?? null);
  readonly firstName = computed(() => this._user()?.fullName.split(' ')[0] ?? '');
  /** Customers and Genies must verify their mobile number before booking / submitting for review. */
  readonly needsPhoneVerification = computed(() => {
    const u = this._user();
    return !!u && u.role !== 'ADMIN' && !u.phoneVerified;
  });
  readonly pendingConsents = computed<LegalKind[]>(() => this._user()?.pendingConsents ?? []);

  token(): string | null {
    return this.accessToken;
  }

  login(body: LoginRequest): Observable<User> {
    return this.http
      .post<AuthResponse>(`${API}/auth/login`, body, { withCredentials: true })
      .pipe(map((res) => this.apply(res)));
  }

  register(body: RegisterRequest): Observable<User> {
    return this.http
      .post<AuthResponse>(`${API}/auth/register`, body, { withCredentials: true })
      .pipe(map((res) => this.apply(res)));
  }

  /** Gets a fresh access token using the refresh cookie. Parallel callers share one request. */
  refresh(): Observable<string> {
    this.refreshInFlight ??= this.http
      .post<AuthResponse>(`${API}/auth/refresh`, {}, { withCredentials: true })
      .pipe(
        map((res) => {
          this.apply(res);
          return res.accessToken;
        }),
        finalize(() => (this.refreshInFlight = null)),
        shareReplay(1),
      );
    return this.refreshInFlight;
  }

  /** Called once at app start: silently restores the session if the refresh cookie is still valid. */
  async restoreSession(): Promise<void> {
    try {
      await firstValueFrom(this.refresh());
    } catch {
      this.clear();
    }
  }

  logout(): void {
    this.clear();
    this.http.post<void>(`${API}/auth/logout`, {}, { withCredentials: true }).subscribe({ error: () => undefined });
    this.router.navigateByUrl('/');
  }

  /** A successful one-time-code login (the API already set the refresh cookie). */
  applyLogin(accessToken: string, user: User): User {
    return this.apply({ accessToken, expiresIn: 0, user });
  }

  /** Re-reads /me (after accepting policies, verifying a phone, scheduling a deletion…). */
  reloadMe(): Observable<User> {
    return this.http.get<User>(`${API}/me`).pipe(map((u) => {
      this._user.set(u);
      return u;
    }));
  }

  /** The API answered 428 CONSENT_REQUIRED: show the re-acceptance dialog. */
  markPendingConsents(kinds: LegalKind[]): void {
    const u = this._user();
    if (u) this._user.set({ ...u, pendingConsents: kinds });
  }

  /** After a profile edit: keep the header and menus in sync without a new login. */
  updateUser(user: User): void {
    this._user.set(user);
  }

  clear(): void {
    this.accessToken = null;
    this._user.set(null);
  }

  homeFor(role: Role | null): string {
    switch (role) {
      case 'GENIE':
        return '/genie';
      case 'ADMIN':
        return '/admin';
      default:
        return '/';
    }
  }

  private apply(res: AuthResponse): User {
    this.accessToken = res.accessToken;
    this._user.set(res.user);
    return res.user;
  }
}
