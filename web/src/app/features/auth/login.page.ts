import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { FormBuilder, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { errorCode, errorMessage } from '../../core/api/api';
import { OtpVerifyResponse } from '../../core/api/models';
import { AuthStore } from '../../core/auth/auth-store';
import { AuthShell } from '../../shared/ui/auth-shell';
import { Logo } from '../../shared/ui/logo';
import { OtpForm } from '../../shared/ui/otp-form';

type Mode = 'password' | 'code';

@Component({
  selector: 'pg-login-page',
  imports: [ReactiveFormsModule, FormsModule, RouterLink, AuthShell, Logo, OtpForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-auth-shell image="/images/hero/hero-ac.webp" headline="Welcome back to ProGenie." [points]="points">
      <div class="card w-full max-w-md p-6 sm:p-8 short:py-6 tiny:p-5">
        <pg-logo [size]="44" class="block tiny:hidden" />
        <h1 class="mt-6 text-3xl font-bold short:mt-4 short:text-[1.75rem] tiny:mt-0">Welcome back</h1>
        <p class="mt-1 text-sm text-muted">Log in with your email or mobile number.</p>

        <div class="mt-5 grid grid-cols-2 gap-2 rounded-full bg-surface p-1 short:mt-4" role="tablist" aria-label="How to log in">
          <button
            type="button"
            role="tab"
            class="rounded-full py-2.5 text-sm font-semibold transition tiny:py-2"
            [class]="mode() === 'password' ? 'bg-white text-ink shadow' : 'text-muted'"
            [attr.aria-selected]="mode() === 'password'"
            (click)="setMode('password')"
          >
            Password
          </button>
          <button
            type="button"
            role="tab"
            class="rounded-full py-2.5 text-sm font-semibold transition tiny:py-2"
            [class]="mode() === 'code' ? 'bg-white text-ink shadow' : 'text-muted'"
            [attr.aria-selected]="mode() === 'code'"
            (click)="setMode('code')"
          >
            One-time code
          </button>
        </div>

        @if (mode() === 'password') {
          <form class="mt-6 grid gap-4 short:mt-5 short:gap-3" [formGroup]="form" (ngSubmit)="submit()">
            <div>
              <label class="label tiny:mb-1" for="identifier">Email or mobile</label>
              <input id="identifier" class="field short:py-2.5 tiny:py-2" formControlName="identifier" autocomplete="username" inputmode="email" />
            </div>
            <div>
              <div class="flex items-baseline justify-between">
                <label class="label tiny:mb-1" for="password">Password</label>
                <a routerLink="/forgot-password" class="text-sm font-semibold text-brand hover:underline">Forgot password?</a>
              </div>
              <input id="password" class="field short:py-2.5 tiny:py-2" type="password" formControlName="password" autocomplete="current-password" />
            </div>
            @if (error()) {
              <div class="rounded-xl bg-red-50 px-4 py-3 text-sm text-red-700" role="alert">
                <p>{{ error() }}</p>
                @if (locked()) {
                  <button type="button" class="mt-2 font-semibold text-brand hover:underline" (click)="setMode('code')">Log in with a one-time code</button>
                }
              </div>
            }
            <button type="submit" class="btn-primary min-h-12 tiny:min-h-11" [disabled]="form.invalid || loading()">
              {{ loading() ? 'Logging in…' : 'Log in' }}
            </button>
          </form>
        } @else if (codeFor(); as target) {
          <div class="mt-6">
            <pg-otp-form purpose="LOGIN" [identifier]="target" submitLabel="Log in" (verified)="onCodeLogin($event)" />
            <button type="button" class="mt-2 w-full text-center text-sm font-semibold text-muted hover:text-brand" (click)="codeFor.set(null)">
              Use a different number or email
            </button>
          </div>
        } @else {
          <form class="mt-6 grid gap-4 short:mt-5 short:gap-3" (ngSubmit)="sendCode()">
            <div>
              <label class="label tiny:mb-1" for="code-identifier">Email or mobile</label>
              <input
                id="code-identifier"
                name="identifier"
                class="field short:py-2.5 tiny:py-2"
                autocomplete="username"
                inputmode="email"
                [ngModel]="codeIdentifier()"
                (ngModelChange)="codeIdentifier.set($event)"
              />
              <p class="mt-1 text-xs text-muted">We'll send a 6-digit code by SMS (mobile) or email.</p>
            </div>
            <button type="submit" class="btn-primary min-h-12 tiny:min-h-11" [disabled]="!codeIdentifier().trim()">Send code</button>
          </form>
        }

        <p class="mt-6 text-center text-sm text-muted short:mt-4 tiny:mt-3">
          New to ProGenie? <a routerLink="/register" class="font-semibold text-brand hover:underline">Create an account</a>
        </p>
        <details class="mt-6 short:mt-4 tiny:mt-3 rounded-xl bg-surface p-4 text-xs text-muted">
          <summary class="cursor-pointer font-semibold text-ink">Local test accounts</summary>
          <p class="mt-2">Password for all: <code>ProGenie&#64;123</code>. Codes arrive in Mailpit (localhost:8025).</p>
          <ul class="mt-1 list-disc pl-5">
            <li>customer&#64;example.com / 9825000005 (customer)</li>
            <li>genie&#64;example.com / 9909000000 (Genie)</li>
            <li>admin&#64;progenie.in (admin)</li>
          </ul>
        </details>
      </div>
    </pg-auth-shell>
  `,
})
export default class LoginPage {
  protected readonly points = ['Admin-verified Genies', 'Upfront prices, travel included', 'Pay after the job is done'];

  /** Where to go after login (set by route guards), bound from the query string. */
  readonly returnUrl = input<string>();

  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    identifier: ['', Validators.required],
    password: ['', Validators.required],
  });
  protected readonly mode = signal<Mode>('password');
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly locked = signal(false);
  protected readonly codeIdentifier = signal('');
  /** Set once the user asked for a code: shows the code entry. */
  protected readonly codeFor = signal<string | null>(null);

  protected setMode(mode: Mode): void {
    this.mode.set(mode);
    this.error.set(null);
    if (mode === 'code' && !this.codeIdentifier()) this.codeIdentifier.set(this.form.controls.identifier.value);
  }

  protected submit(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set(null);
    this.locked.set(false);
    this.auth.login(this.form.getRawValue()).subscribe({
      next: (user) => this.router.navigateByUrl(this.safeReturnUrl() ?? this.auth.homeFor(user.role)),
      error: (err) => {
        this.locked.set(errorCode(err) === 'LOGIN_LOCKED');
        this.error.set(errorMessage(err, 'Login failed'));
        this.loading.set(false);
      },
    });
  }

  protected sendCode(): void {
    const value = this.codeIdentifier().trim();
    if (value) this.codeFor.set(value);
  }

  protected onCodeLogin(res: OtpVerifyResponse): void {
    if (!res.accessToken || !res.user) return;
    const user = this.auth.applyLogin(res.accessToken, res.user);
    this.router.navigateByUrl(this.safeReturnUrl() ?? this.auth.homeFor(user.role));
  }

  /** Only allow in-app paths, never an external URL. */
  private safeReturnUrl(): string | null {
    const url = this.returnUrl();
    return url && url.startsWith('/') && !url.startsWith('//') ? url : null;
  }
}
