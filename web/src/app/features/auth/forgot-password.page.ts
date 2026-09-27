import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { AccountApi } from '../../core/api/account-api';
import { errorMessage } from '../../core/api/api';
import { OtpVerifyResponse } from '../../core/api/models';
import { ToastService } from '../../core/ui/feedback';
import { AuthShell } from '../../shared/ui/auth-shell';
import { Spinner } from '../../shared/ui/kit';
import { Logo } from '../../shared/ui/logo';
import { OtpForm } from '../../shared/ui/otp-form';

/** Three steps: who are you → the code we sent → a new password. */
@Component({
  selector: 'pg-forgot-password-page',
  imports: [FormsModule, RouterLink, AuthShell, Logo, OtpForm, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-auth-shell image="/images/hero/hero-ac.webp" headline="Locked out? Back in a minute." [points]="points">
      <div class="card w-full max-w-md p-6 sm:p-8 short:py-6 tiny:p-5">
        <pg-logo [size]="44" class="block tiny:hidden" />
        <h1 class="mt-6 text-3xl font-bold short:mt-4 short:text-[1.75rem] tiny:mt-0">Reset your password</h1>
        <ol class="mt-3 flex gap-2 text-xs font-semibold" aria-label="Steps">
          @for (label of steps; track label; let i = $index) {
            <li class="flex-1 rounded-full px-2 py-1 text-center" [class]="i <= step() ? 'bg-brand text-white' : 'bg-surface text-muted'">{{ label }}</li>
          }
        </ol>

        @switch (step()) {
          @case (0) {
            <form class="mt-6 grid gap-4" (ngSubmit)="next()">
              <div>
                <label class="label" for="fp-identifier">Email or mobile</label>
                <input id="fp-identifier" name="identifier" class="field" autocomplete="username" [(ngModel)]="identifier" />
                <p class="mt-1 text-xs text-muted">If an account exists, we'll send it a 6-digit code.</p>
              </div>
              <button type="submit" class="btn-primary min-h-12" [disabled]="!identifier.trim()">Send code</button>
            </form>
          }
          @case (1) {
            <div class="mt-6">
              <pg-otp-form purpose="RESET_PASSWORD" [identifier]="identifier.trim()" (verified)="onCode($event)" />
              <button type="button" class="mt-2 w-full text-center text-sm font-semibold text-muted hover:text-brand" (click)="step.set(0)">Change number or email</button>
            </div>
          }
          @case (2) {
            <form class="mt-6 grid gap-4" (ngSubmit)="save()" #f="ngForm">
              <div>
                <label class="label" for="fp-new">New password <span class="font-normal text-muted">(8 to 72 characters)</span></label>
                <input id="fp-new" name="password" type="password" class="field" required minlength="8" maxlength="72" autocomplete="new-password" [(ngModel)]="password" />
              </div>
              <div>
                <label class="label" for="fp-confirm">Confirm new password</label>
                <input id="fp-confirm" name="confirm" type="password" class="field" required autocomplete="new-password" [(ngModel)]="confirm" />
                @if (confirm && confirm !== password) {
                  <p class="mt-1 text-xs text-rose-700">Passwords don't match.</p>
                }
              </div>
              @if (error()) {
                <p class="rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ error() }}</p>
              }
              <button type="submit" class="btn-primary min-h-12" [disabled]="!f.valid || confirm !== password || busy()">
                @if (busy()) {
                  <pg-spinner />
                }
                Set new password
              </button>
              <p class="text-xs text-muted">You'll be signed out on every device, then you can log in with the new password.</p>
            </form>
          }
        }

        <p class="mt-6 text-center text-sm text-muted">
          Remembered it? <a routerLink="/login" class="font-semibold text-brand hover:underline">Back to log in</a>
        </p>
      </div>
    </pg-auth-shell>
  `,
})
export default class ForgotPasswordPage {
  protected readonly points = ['A code by SMS or email', 'No support call needed', 'Every other device is signed out'];
  protected readonly steps = ['Account', 'Code', 'New password'];

  private readonly api = inject(AccountApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  protected readonly step = signal(0);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected identifier = '';
  protected password = '';
  protected confirm = '';
  private resetToken: string | null = null;

  protected next(): void {
    if (this.identifier.trim()) this.step.set(1);
  }

  protected onCode(res: OtpVerifyResponse): void {
    this.resetToken = res.resetToken;
    this.step.set(2);
  }

  protected save(): void {
    if (!this.resetToken) return;
    this.busy.set(true);
    this.error.set(null);
    this.api.resetPassword(this.resetToken, this.password).subscribe({
      next: () => {
        this.busy.set(false);
        this.toast.success('Password changed. Please log in.');
        this.router.navigateByUrl('/login');
      },
      error: (err) => {
        this.busy.set(false);
        this.error.set(errorMessage(err, 'Could not change the password.'));
      },
    });
  }
}
