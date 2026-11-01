import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { errorCode, errorMessage } from '../../core/api/api';
import { AuthStore } from '../../core/auth/auth-store';
import { CityStore } from '../../core/city/city-store';
import { Spinner } from './kit';

/**
 * "Notify me when ProGenie arrives" (Phase 3.5B), shown wherever we say we don't serve a place yet: a PIN code, or a
 * map point outside our cities. Guests type a mobile number or an e-mail; a logged-in user's number is filled in.
 * We send one message when the area opens, then forget the details.
 */
@Component({
  selector: 'pg-notify-me',
  imports: [FormsModule, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @if (done(); as d) {
      <p class="text-sm" [class]="dark() ? 'text-emerald-300' : 'text-emerald-700'" role="status" data-testid="notify-done">
        Done. We'll message {{ d }} once when ProGenie arrives{{ pincode() ? ' in ' + pincode() : '' }}.
      </p>
    } @else if (!open()) {
      <button
        type="button"
        class="text-sm font-semibold underline underline-offset-2"
        [class]="dark() ? 'text-white hover:text-brand-mist' : 'text-brand hover:text-brand-600'"
        (click)="start()"
        data-testid="notify-open"
      >
        Notify me when ProGenie arrives
      </button>
    } @else {
      <!-- not a <form>: this sits inside the address form and the PIN code check, which must not submit -->
      <div class="mt-1 space-y-2" role="group" aria-label="Notify me when ProGenie arrives" data-testid="notify-form">
        <label class="sr-only" [for]="id + '-contact'">Mobile number or e-mail</label>
        <div class="flex gap-2">
          <input
            [id]="id + '-contact'"
            class="field min-w-0 flex-1 py-2.5 text-sm"
            [class.text-ink]="dark()"
            autocomplete="off"
            maxlength="254"
            [(ngModel)]="contact"
            [ngModelOptions]="{ standalone: true }"
            (keydown.enter)="$event.preventDefault(); send()"
            placeholder="Mobile number or e-mail"
          />
          <button type="button" class="btn-primary shrink-0 px-4 text-sm" [disabled]="busy() || !contact.trim() || !consent" (click)="send()">
            @if (busy()) {
              <pg-spinner [size]="14" />
            }
            Notify me
          </button>
        </div>
        <label class="flex items-start gap-2 text-xs" [class]="dark() ? 'text-brand-mist' : 'text-muted'">
          <input type="checkbox" class="mt-0.5 size-4 rounded accent-brand" [(ngModel)]="consent" [ngModelOptions]="{ standalone: true }" />
          <span>You may contact me once when ProGenie arrives here. My details are deleted after that (or after 12 months).</span>
        </label>
        @if (error()) {
          <p class="text-xs" [class]="dark() ? 'text-rose-300' : 'text-rose-700'" role="alert">{{ error() }}</p>
        }
      </div>
    }
  `,
})
export class NotifyMe {
  readonly pincode = input<string | null>(null);
  readonly lat = input<number | null>(null);
  readonly lng = input<number | null>(null);
  /** 'dark' inside the header's dark mobile menu. */
  readonly tone = input<'light' | 'dark'>('light');

  private readonly cities = inject(CityStore);
  private readonly auth = inject(AuthStore);
  private static next = 0;
  protected readonly id = `notify-${NotifyMe.next++}`;
  protected readonly dark = computed(() => this.tone() === 'dark');
  protected readonly open = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly done = signal<string | null>(null);
  protected contact = '';
  protected consent = false;

  protected start(): void {
    const u = this.auth.user();
    this.contact = u?.phone ?? u?.email ?? '';
    this.open.set(true);
  }

  protected send(): void {
    const value = this.contact.trim();
    if (!value || !this.consent || this.busy()) return;
    const isEmail = value.includes('@');
    const where = this.pincode() ? { pincode: this.pincode() } : { lat: this.lat(), lng: this.lng() };
    this.busy.set(true);
    this.error.set(null);
    this.cities
      .notifyMe({ ...where, phone: isEmail ? null : value, email: isEmail ? value : null, consent: this.consent })
      .subscribe({
        next: (r) => {
          this.busy.set(false);
          this.done.set(r.contact);
        },
        error: (err) => {
          this.busy.set(false);
          this.error.set(errorCode(err) === 'ALREADY_SERVED' ? errorMessage(err) + '. Check it again above.' : errorMessage(err));
        },
      });
  }
}
