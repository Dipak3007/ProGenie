import { ChangeDetectionStrategy, Component, OnInit, inject, input, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { errorMessage } from '../../core/api/api';
import { AuthStore } from '../../core/auth/auth-store';
import { AuthShell } from '../../shared/ui/auth-shell';
import { Logo } from '../../shared/ui/logo';
import { OtpForm } from '../../shared/ui/otp-form';
import { OtpVerifyResponse } from '../../core/api/models';
import { ToastService } from '../../core/ui/feedback';

@Component({
  selector: 'pg-register-page',
  imports: [ReactiveFormsModule, RouterLink, AuthShell, Logo, OtpForm],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <pg-auth-shell image="/images/hero/genie-join.webp" headline="Join ProGenie in under a minute." [points]="points">
      <div class="card w-full max-w-lg p-6 sm:p-8 short:py-6 tiny:p-5">
        <pg-logo [size]="44" class="block tiny:hidden" />
        @if (verifying()) {
          <h1 class="mt-6 text-3xl font-bold short:mt-4 short:text-[1.75rem] tiny:mt-0">Verify your mobile</h1>
          <p class="mt-1 text-sm text-muted">One last step: confirm {{ form.controls.phone.value }} is yours.</p>
          <div class="mt-6">
            <pg-otp-form purpose="VERIFY_PHONE" submitLabel="Verify and continue" (verified)="onVerified($event)" />
          </div>
          <button type="button" class="mt-3 w-full text-center text-sm font-semibold text-muted hover:text-brand" (click)="done()">I'll do it later</button>
        } @else {
        <h1 class="mt-6 text-3xl font-bold short:mt-4 short:text-[1.75rem] tiny:mt-0">Create your account</h1>

        <div class="mt-5 grid grid-cols-2 gap-2 rounded-full bg-surface p-1 short:mt-4 tiny:mt-3" role="radiogroup" aria-label="Account type">
          <button
            type="button"
            role="radio"
            class="rounded-full py-2.5 text-sm font-semibold transition tiny:py-2"
            [class]="form.controls.role.value === 'CUSTOMER' ? 'bg-white text-ink shadow' : 'text-muted'"
            [attr.aria-checked]="form.controls.role.value === 'CUSTOMER'"
            (click)="form.controls.role.setValue('CUSTOMER')"
          >
            I need a service
          </button>
          <button
            type="button"
            role="radio"
            class="rounded-full py-2.5 text-sm font-semibold transition tiny:py-2"
            [class]="form.controls.role.value === 'GENIE' ? 'bg-white text-ink shadow' : 'text-muted'"
            [attr.aria-checked]="form.controls.role.value === 'GENIE'"
            (click)="form.controls.role.setValue('GENIE')"
          >
            I'm a professional
          </button>
        </div>
        @if (form.controls.role.value === 'GENIE') {
          <p class="mt-3 rounded-xl bg-brand-mist px-4 py-3 text-sm text-brand-600 short:py-2 short:text-xs">
            After signing up you'll complete your profile and KYC. An admin verifies every Genie before you can receive bookings.
          </p>
        }

        <form class="mt-6 grid gap-4 sm:grid-cols-2 short:mt-4 short:gap-3" [formGroup]="form" (ngSubmit)="submit()">
          <div class="sm:col-span-2">
            <label class="label tiny:mb-1" for="fullName">Full name</label>
            <input id="fullName" class="field short:py-2.5 tiny:py-2" formControlName="fullName" autocomplete="name" />
          </div>
          <div>
            <label class="label tiny:mb-1" for="phone">Mobile number</label>
            <input id="phone" class="field short:py-2.5 tiny:py-2" formControlName="phone" inputmode="numeric" autocomplete="tel-national" placeholder="98XXXXXXXX" />
          </div>
          <div>
            <label class="label tiny:mb-1" for="email">Email <span class="font-normal text-muted">(optional)</span></label>
            <input id="email" class="field short:py-2.5 tiny:py-2" type="email" formControlName="email" autocomplete="email" />
          </div>
          <div class="sm:col-span-2">
            <label class="label tiny:mb-1" for="password">Password <span class="font-normal text-muted">(at least 8 characters)</span></label>
            <input id="password" class="field short:py-2.5 tiny:py-2" type="password" formControlName="password" autocomplete="new-password" />
          </div>
          <div class="space-y-2 text-sm sm:col-span-2">
            <label class="flex items-start gap-3">
              <input type="checkbox" class="mt-0.5 size-5 shrink-0 accent-brand" formControlName="acceptTerms" />
              <span>
                I agree to the <a routerLink="/legal/terms" target="_blank" class="font-semibold text-brand hover:underline">Terms of Service</a>
                and the <a routerLink="/legal/privacy" target="_blank" class="font-semibold text-brand hover:underline">Privacy Policy</a>.
              </span>
            </label>
            @if (form.controls.role.value === 'GENIE') {
              <label class="flex items-start gap-3">
                <input type="checkbox" class="mt-0.5 size-5 shrink-0 accent-brand" formControlName="acceptGenieAgreement" />
                <span>I agree to the <a routerLink="/legal/genie-agreement" target="_blank" class="font-semibold text-brand hover:underline">Genie Partner Agreement</a>.</span>
              </label>
            }
            <label class="flex items-start gap-3 text-muted">
              <input type="checkbox" class="mt-0.5 size-5 shrink-0 accent-brand" formControlName="marketingOptIn" />
              <span>Send me offers and tips (optional, you can switch this off any time).</span>
            </label>
          </div>
          @if (error()) {
            <p class="rounded-xl bg-red-50 px-4 py-3 text-sm text-red-700 sm:col-span-2" role="alert">{{ error() }}</p>
          }
          <button type="submit" class="btn-primary min-h-12 tiny:min-h-11 sm:col-span-2" [disabled]="form.invalid || !consentsGiven() || loading()">
            {{ loading() ? 'Creating account…' : 'Sign up' }}
          </button>
        </form>

        <p class="mt-6 text-center text-sm text-muted short:mt-4 tiny:mt-3">
          Already have an account? <a routerLink="/login" class="font-semibold text-brand hover:underline">Log in</a>
        </p>
        }
      </div>
    </pg-auth-shell>
  `,
})
export default class RegisterPage implements OnInit {
  protected readonly points = ['Book trusted help for your home', 'Or earn as a Genie: keep 95% of your fee', 'Sign up with just your mobile number'];

  /** ?role=GENIE preselects the professional sign-up. */
  readonly role = input<string>();

  private readonly auth = inject(AuthStore);
  private readonly router = inject(Router);
  protected readonly form = inject(FormBuilder).nonNullable.group({
    role: ['CUSTOMER' as 'CUSTOMER' | 'GENIE'],
    fullName: ['', [Validators.required, Validators.maxLength(100)]],
    phone: ['', [Validators.required, Validators.pattern(/^[6-9][0-9]{9}$/)]],
    email: ['', [Validators.email]],
    password: ['', [Validators.required, Validators.minLength(8), Validators.maxLength(72)]],
    acceptTerms: [false],
    acceptGenieAgreement: [false],
    marketingOptIn: [false],
  });
  private readonly toast = inject(ToastService);
  /** After sign-up: the verify-phone step. */
  protected readonly verifying = signal(false);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected consentsGiven(): boolean {
    const v = this.form.getRawValue();
    return v.acceptTerms && (v.role !== 'GENIE' || v.acceptGenieAgreement);
  }

  protected onVerified(res: OtpVerifyResponse): void {
    if (res.user) this.auth.updateUser(res.user);
    this.toast.success('Mobile number verified. Welcome to ProGenie!');
    this.done();
  }

  protected done(): void {
    this.router.navigateByUrl(this.auth.homeFor(this.auth.role()));
  }

  ngOnInit(): void {
    if (this.role() === 'GENIE') {
      this.form.controls.role.setValue('GENIE');
    }
  }

  protected submit(): void {
    if (this.form.invalid) return;
    this.loading.set(true);
    this.error.set(null);
    const v = this.form.getRawValue();
    this.auth.register({ ...v, email: v.email || null, acceptGenieAgreement: v.role === 'GENIE' && v.acceptGenieAgreement }).subscribe({
      next: () => {
        this.loading.set(false);
        this.verifying.set(true);
      },
      error: (err) => {
        this.error.set(errorMessage(err, 'Sign-up failed'));
        this.loading.set(false);
      },
    });
  }
}
