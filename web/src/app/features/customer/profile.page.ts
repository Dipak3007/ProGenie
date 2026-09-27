import { ChangeDetectionStrategy, Component, effect, inject, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { errorMessage } from '../../core/api/api';
import { CustomerApi } from '../../core/api/customer-api';
import { AuthStore } from '../../core/auth/auth-store';
import { PhoneVerification } from '../../core/auth/phone-verification';
import { OtpVerifyResponse } from '../../core/api/models';
import { Dialog } from '../../shared/ui/dialog';
import { OtpForm } from '../../shared/ui/otp-form';
import { ToastService } from '../../core/ui/feedback';
import { PageHead, Spinner } from '../../shared/ui/kit';

/** Name / email and password. Used by customers here and by Genies and admins through the same component. */
@Component({
  selector: 'pg-profile-page',
  imports: [FormsModule, PageHead, Spinner, Dialog, OtpForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-page-head title="Profile" subtitle="Your contact details and password." />

    <div class="mt-6 grid gap-6 xl:grid-cols-2">
      <section class="card p-5 sm:p-6" aria-labelledby="details-h">
        <h2 id="details-h" class="text-lg font-bold">Your details</h2>
        <form class="mt-4 space-y-4" (ngSubmit)="saveProfile()" #pf="ngForm">
          <div>
            <label class="label" for="p-name">Full name</label>
            <input id="p-name" name="fullName" class="field" required minlength="2" maxlength="100" [(ngModel)]="fullName" autocomplete="name" />
          </div>
          <div>
            <label class="label" for="p-email">Email</label>
            <input id="p-email" name="email" type="email" class="field" email maxlength="120" [(ngModel)]="email" autocomplete="email" />
            @if (auth.user()?.email) {
              @if (auth.user()?.emailVerified) {
                <p class="mt-1 text-xs font-semibold text-emerald-700">Verified. Receipts are emailed here.</p>
              } @else {
                <p class="mt-1 text-xs text-muted">
                  Not verified yet, so we can't email receipts.
                  <button type="button" class="font-semibold text-brand hover:underline" (click)="verifyEmailOpen.set(true)">Verify email</button>
                </p>
              }
            }
          </div>
          <div>
            <span class="label">Mobile</span>
            <p class="field bg-surface text-muted">{{ auth.user()?.phone }}</p>
            @if (auth.user()?.phoneVerified) {
              <p class="mt-1 text-xs"><span class="font-semibold text-emerald-700">Verified.</span> <span class="text-muted">Your mobile number is your login. Contact support to change it.</span></p>
            } @else {
              <p class="mt-1 text-xs text-muted">
                Not verified yet.
                <button type="button" class="font-semibold text-brand hover:underline" (click)="phone.open()">Verify mobile</button>
              </p>
            }
          </div>
          <button type="submit" class="btn-primary" [disabled]="savingProfile() || !pf.valid">
            @if (savingProfile()) {
              <pg-spinner />
            }
            Save details
          </button>
        </form>
      </section>

      <section class="card p-5 sm:p-6" aria-labelledby="pw-h">
        <h2 id="pw-h" class="text-lg font-bold">Change password</h2>
        <p class="mt-1 text-sm text-muted">You'll stay signed in here; other devices are signed out.</p>
        <form class="mt-4 space-y-4" (ngSubmit)="changePassword()" #pw="ngForm">
          <div>
            <label class="label" for="p-current">Current password</label>
            <input id="p-current" name="current" type="password" class="field" required [(ngModel)]="current" autocomplete="current-password" />
          </div>
          <div>
            <label class="label" for="p-new">New password <span class="font-normal text-muted">(8 to 72 characters)</span></label>
            <input id="p-new" name="next" type="password" class="field" required minlength="8" maxlength="72" [(ngModel)]="next" autocomplete="new-password" />
          </div>
          <div>
            <label class="label" for="p-confirm">Confirm new password</label>
            <input id="p-confirm" name="confirm" type="password" class="field" required [(ngModel)]="confirmNext" autocomplete="new-password" />
            @if (confirmNext && confirmNext !== next) {
              <p class="mt-1 text-xs text-rose-700">Passwords don't match.</p>
            }
          </div>
          @if (pwError()) {
            <p class="rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ pwError() }}</p>
          }
          <button type="submit" class="btn-primary" [disabled]="savingPw() || !pw.valid || confirmNext !== next">
            @if (savingPw()) {
              <pg-spinner />
            }
            Update password
          </button>
        </form>
      </section>
    </div>

    <pg-dialog [open]="verifyEmailOpen()" heading="Verify your email" size="sm" (closed)="verifyEmailOpen.set(false)">
      @if (verifyEmailOpen()) {
        <pg-otp-form purpose="VERIFY_EMAIL" submitLabel="Verify" (verified)="onEmailVerified($event)" />
      }
    </pg-dialog>
  `,
})
export default class ProfilePage {
  protected readonly auth = inject(AuthStore);
  protected readonly phone = inject(PhoneVerification);
  protected readonly verifyEmailOpen = signal(false);
  private readonly api = inject(CustomerApi);
  private readonly toast = inject(ToastService);

  protected fullName = '';
  protected email = '';
  protected current = '';
  protected next = '';
  protected confirmNext = '';
  protected readonly savingProfile = signal(false);
  protected readonly savingPw = signal(false);
  protected readonly pwError = signal<string | null>(null);

  constructor() {
    effect(() => {
      const u = this.auth.user();
      untracked(() => {
        this.fullName = u?.fullName ?? '';
        this.email = u?.email ?? '';
      });
    });
  }

  protected saveProfile(): void {
    this.savingProfile.set(true);
    this.api.updateProfile({ fullName: this.fullName.trim(), email: this.email.trim() || null }).subscribe({
      next: (u) => {
        this.savingProfile.set(false);
        this.auth.updateUser(u);
        this.toast.success('Details saved');
      },
      error: (err) => {
        this.savingProfile.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected onEmailVerified(res: OtpVerifyResponse): void {
    if (res.user) this.auth.updateUser(res.user);
    this.verifyEmailOpen.set(false);
    this.toast.success('Email verified');
  }

  protected changePassword(): void {
    this.savingPw.set(true);
    this.pwError.set(null);
    this.api.changePassword({ currentPassword: this.current, newPassword: this.next }).subscribe({
      next: () => {
        this.savingPw.set(false);
        this.current = this.next = this.confirmNext = '';
        this.toast.success('Password updated. Other devices were signed out.');
      },
      error: (err) => {
        this.savingPw.set(false);
        this.pwError.set(errorMessage(err, 'Could not change the password.'));
      },
    });
  }
}
