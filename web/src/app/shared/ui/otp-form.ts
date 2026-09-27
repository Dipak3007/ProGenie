import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, OnInit, inject, input, output, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { LucideDynamicIcon, LucideMail as Mail, LucideMessageSquare as MessageSquare } from '@lucide/angular';

import { AccountApi } from '../../core/api/account-api';
import { errorCode, errorMessage } from '../../core/api/api';
import { OtpChallenge, OtpPurpose, OtpVerifyResponse, ProblemDetail } from '../../core/api/models';
import { Spinner } from './kit';

/**
 * Asks the API for a 6-digit code and checks it. Used for login, password reset and phone/email verification.
 * Handles the resend countdown, "too soon" / rate-limit answers and wrong-code attempts.
 * Emits {@link verified} with the API's answer (a login token, a reset token or the updated user).
 */
@Component({
  selector: 'pg-otp-form',
  imports: [FormsModule, LucideDynamicIcon, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (challenge(); as c) {
      <div class="flex items-start gap-3 rounded-2xl bg-brand-mist/60 p-4 text-sm">
        <svg [lucideIcon]="c.channel === 'SMS' ? MessageSquare : Mail" [size]="18" class="mt-0.5 shrink-0 text-brand-600" aria-hidden="true"></svg>
        <p>
          We sent a 6-digit code {{ c.channel === 'SMS' ? 'by SMS to' : 'to' }} <span class="font-semibold">{{ c.destination }}</span>.
          It works for {{ minutes(c) }} minutes.
        </p>
      </div>
      <form class="mt-4" (ngSubmit)="verify()">
        <label class="label" for="otp-code">Enter the code</label>
        <input
          #codeInput
          id="otp-code"
          name="code"
          class="field text-center font-display text-2xl tracking-[0.5em]"
          inputmode="numeric"
          autocomplete="one-time-code"
          maxlength="6"
          placeholder="••••••"
          [ngModel]="code()"
          (ngModelChange)="onCode($event)"
        />
        @if (error()) {
          <p class="mt-3 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ error() }}</p>
        }
        <button type="submit" class="btn-primary mt-4 min-h-12 w-full" [disabled]="code().length !== 6 || busy()">
          @if (busy()) {
            <pg-spinner />
          }
          {{ submitLabel() }}
        </button>
      </form>
      <p class="mt-4 text-center text-sm text-muted">
        Didn't get it?
        @if (countdown() > 0) {
          <span>Send a new code in {{ countdown() }} s</span>
        } @else {
          <button type="button" class="font-semibold text-brand hover:underline" [disabled]="busy()" (click)="start()">Send a new code</button>
        }
      </p>
    } @else {
      @if (error()) {
        <p class="rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ error() }}</p>
        @if (countdown() > 0) {
          <p class="mt-3 text-center text-sm text-muted">You can ask again in {{ countdown() }} s.</p>
        } @else {
          <button type="button" class="btn-ghost mt-4 w-full" (click)="start()">Try again</button>
        }
      } @else {
        <p class="flex items-center justify-center gap-2 py-6 text-sm text-muted"><pg-spinner /> Sending your code…</p>
      }
    }
  `,
})
export class OtpForm implements OnInit {
  readonly purpose = input.required<OtpPurpose>();
  /** Phone or email; leave empty for VERIFY_* (the account's own). */
  readonly identifier = input<string | null>(null);
  readonly submitLabel = input('Continue');
  readonly verified = output<OtpVerifyResponse>();

  private readonly api = inject(AccountApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly codeInput = viewChild<ElementRef<HTMLInputElement>>('codeInput');

  protected readonly challenge = signal<OtpChallenge | null>(null);
  protected readonly code = signal('');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly countdown = signal(0);
  private timer: ReturnType<typeof setInterval> | null = null;

  constructor() {
    this.destroyRef.onDestroy(() => this.stopTimer());
  }

  ngOnInit(): void {
    this.start();
  }

  /** Requests a (new) code. */
  start(): void {
    this.busy.set(true);
    this.error.set(null);
    this.code.set('');
    this.api.requestOtp(this.purpose(), this.identifier()).subscribe({
      next: (c) => {
        this.busy.set(false);
        this.challenge.set(c);
        this.runCountdown(c.resendAfterSeconds);
        setTimeout(() => this.codeInput()?.nativeElement.focus());
      },
      error: (err) => {
        this.busy.set(false);
        const body = (err?.error ?? null) as ProblemDetail | null;
        if (body?.retryAfterSeconds) this.runCountdown(body.retryAfterSeconds);
        this.error.set(errorMessage(err, 'Could not send a code.'));
      },
    });
  }

  protected onCode(value: string): void {
    const digits = (value ?? '').replace(/\D/g, '').slice(0, 6);
    this.code.set(digits);
    if (digits.length === 6 && !this.busy()) this.verify();
  }

  protected verify(): void {
    const c = this.challenge();
    if (!c || this.code().length !== 6 || this.busy()) return;
    this.busy.set(true);
    this.error.set(null);
    this.api.verifyOtp(c.challengeId, this.code()).subscribe({
      next: (res) => {
        this.busy.set(false);
        this.verified.emit(res);
      },
      error: (err) => {
        this.busy.set(false);
        this.code.set('');
        const body = (err?.error ?? null) as ProblemDetail | null;
        if (errorCode(err) === 'OTP_EXPIRED') {
          this.error.set(`${errorMessage(err)}.`.replace('..', '.'));
          this.countdown.set(0);
        } else if (body?.attemptsLeft !== undefined) {
          this.error.set(`That code is not right. ${body.attemptsLeft} ${body.attemptsLeft === 1 ? 'try' : 'tries'} left.`);
        } else {
          this.error.set(errorMessage(err, 'Could not check the code.'));
        }
      },
    });
  }

  protected minutes(c: OtpChallenge): number {
    return Math.max(1, Math.round(c.expiresInSeconds / 60));
  }

  private runCountdown(seconds: number): void {
    this.stopTimer();
    this.countdown.set(seconds);
    this.timer = setInterval(() => {
      const next = this.countdown() - 1;
      this.countdown.set(Math.max(0, next));
      if (next <= 0) this.stopTimer();
    }, 1000);
  }

  private stopTimer(): void {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  protected readonly Mail = Mail;
  protected readonly MessageSquare = MessageSquare;
}
