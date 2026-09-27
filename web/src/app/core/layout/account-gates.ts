import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { filter, map } from 'rxjs';
import {
  LucideDynamicIcon,
  LucideFileText as FileText,
  LucideShieldAlert as ShieldAlert,
  LucideSmartphone as Smartphone,
  LucideTrash2 as Trash,
} from '@lucide/angular';

import { AccountApi, LEGAL_SLUG, LEGAL_TITLE } from '../api/account-api';
import { errorMessage } from '../api/api';
import { LegalKind, OtpVerifyResponse } from '../api/models';
import { AuthStore } from '../auth/auth-store';
import { PhoneVerification } from '../auth/phone-verification';
import { ToastService } from '../ui/feedback';
import { Dialog } from '../../shared/ui/dialog';
import { Spinner } from '../../shared/ui/kit';
import { OtpForm } from '../../shared/ui/otp-form';

/**
 * Account prompts shown anywhere in the app, in the app shell:
 * - a banner while the mobile number isn't verified (plus the verify dialog, also opened on 403 PHONE_NOT_VERIFIED),
 * - a banner while an account deletion is scheduled (with "Cancel deletion"),
 * - the policy re-acceptance dialog when policies are pending (after login, or on 428 CONSENT_REQUIRED).
 */
@Component({
  selector: 'pg-account-gates',
  imports: [DatePipe, FormsModule, RouterLink, LucideDynamicIcon, Dialog, OtpForm, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (showVerifyBanner()) {
      <div class="border-b border-amber-200 bg-amber-50">
        <div class="container-page flex flex-wrap items-center gap-x-4 gap-y-2 py-2.5 text-sm text-amber-900">
          <svg [lucideIcon]="Smartphone" [size]="18" class="shrink-0" aria-hidden="true"></svg>
          <p class="min-w-0 flex-1">
            Verify your mobile number
            {{ auth.role() === 'GENIE' ? 'to submit your profile for review.' : 'before your first booking.' }}
          </p>
          <button type="button" class="btn min-h-9 bg-amber-900 px-4 text-white hover:bg-amber-950" (click)="phone.open()">Verify now</button>
        </div>
      </div>
    }
    @if (auth.user()?.deletionScheduledFor; as when) {
      <div class="border-b border-rose-200 bg-rose-50">
        <div class="container-page flex flex-wrap items-center gap-x-4 gap-y-2 py-2.5 text-sm text-rose-900">
          <svg [lucideIcon]="Trash" [size]="18" class="shrink-0" aria-hidden="true"></svg>
          <p class="min-w-0 flex-1">Your account will be deleted on {{ when | date: 'd MMM yyyy' }}.</p>
          <button type="button" class="btn min-h-9 border border-rose-300 bg-white px-4 text-rose-800 hover:bg-rose-100" [disabled]="busy()" (click)="cancelDeletion()">
            Keep my account
          </button>
        </div>
      </div>
    }

    <pg-dialog [open]="phone.isOpen()" heading="Verify your mobile number" subheading="One quick code keeps bookings safe for everyone." size="sm" (closed)="phone.close()">
      @if (phone.isOpen()) {
        <pg-otp-form purpose="VERIFY_PHONE" submitLabel="Verify" (verified)="onPhoneVerified($event)" />
      }
    </pg-dialog>

    <pg-dialog [open]="showConsent()" heading="We've updated our policies" subheading="Please review and accept them to keep using ProGenie." size="md" (closed)="dismissConsent()">
      @if (showConsent()) {
        <ul class="space-y-2">
          @for (kind of auth.pendingConsents(); track kind) {
            <li>
              <a [routerLink]="['/legal', slug[kind]]" target="_blank" class="flex items-center gap-3 rounded-2xl border border-line p-3 hover:border-brand-soft">
                <svg [lucideIcon]="FileText" [size]="18" class="shrink-0 text-brand" aria-hidden="true"></svg>
                <span class="flex-1 font-semibold">{{ title[kind] }}</span>
                <span class="text-sm text-brand">Read</span>
              </a>
            </li>
          }
        </ul>
        <label class="mt-4 flex items-start gap-3 text-sm">
          <input type="checkbox" class="mt-0.5 size-5 accent-brand" [ngModel]="agree()" (ngModelChange)="agree.set($event)" name="agree" />
          <span>I have read and accept the updated {{ auth.pendingConsents().length > 1 ? 'policies' : 'policy' }}.</span>
        </label>
        <div class="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <button type="button" class="btn-ghost" (click)="auth.logout()">Log out</button>
          <button type="button" class="btn-primary" [disabled]="!agree() || busy()" (click)="acceptConsents()">
            @if (busy()) {
              <pg-spinner />
            }
            Accept and continue
          </button>
        </div>
        <p class="mt-3 flex items-center gap-2 text-xs text-muted">
          <svg [lucideIcon]="ShieldAlert" [size]="14" aria-hidden="true"></svg>
          Until you accept, booking and accepting jobs are paused.
        </p>
      }
    </pg-dialog>
  `,
})
export class AccountGates {
  protected readonly auth = inject(AuthStore);
  protected readonly phone = inject(PhoneVerification);
  private readonly api = inject(AccountApi);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);

  protected readonly busy = signal(false);
  protected readonly agree = signal(false);
  /** The user closed the dialog; it comes back on the next 428 or reload. */
  private readonly consentDismissedFor = signal<string | null>(null);
  protected readonly slug = LEGAL_SLUG;
  protected readonly title = LEGAL_TITLE;

  private readonly url = toSignal(
    this.router.events.pipe(filter((e) => e instanceof NavigationEnd), map(() => this.router.url)),
    { initialValue: this.router.url },
  );

  /** Not on the sign-up page (it has its own verify step) or the legal pages. */
  protected readonly showVerifyBanner = computed(() => this.auth.needsPhoneVerification() && !this.url().startsWith('/register'));
  protected readonly showConsent = computed(() => {
    const pending = this.auth.pendingConsents();
    return pending.length > 0 && !this.url().startsWith('/legal') && this.consentDismissedFor() !== pending.join();
  });

  protected onPhoneVerified(res: OtpVerifyResponse): void {
    if (res.user) this.auth.updateUser(res.user);
    this.phone.close();
    this.toast.success('Mobile number verified');
  }

  protected acceptConsents(): void {
    const kinds: LegalKind[] = [...this.auth.pendingConsents()];
    this.busy.set(true);
    this.api.acceptConsents(kinds).subscribe({
      next: (res) => {
        this.busy.set(false);
        this.agree.set(false);
        this.auth.markPendingConsents(res.pendingConsents);
        this.toast.success('Thanks! You can carry on.');
      },
      error: (err) => {
        this.busy.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected dismissConsent(): void {
    this.consentDismissedFor.set(this.auth.pendingConsents().join());
  }

  protected cancelDeletion(): void {
    this.busy.set(true);
    this.api.cancelDeletion().subscribe({
      next: () => {
        this.busy.set(false);
        this.auth.reloadMe().subscribe({ error: () => undefined });
        this.toast.success('Deletion cancelled. Your account stays.');
      },
      error: (err) => {
        this.busy.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected readonly FileText = FileText;
  protected readonly ShieldAlert = ShieldAlert;
  protected readonly Smartphone = Smartphone;
  protected readonly Trash = Trash;
}
