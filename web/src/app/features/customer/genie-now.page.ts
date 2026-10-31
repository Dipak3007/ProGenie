import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, signal, untracked } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CurrencyPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { filter } from 'rxjs';
import {
  LucideArrowLeft as ArrowLeft,
  LucideBanknote as Banknote,
  LucideCreditCard as CreditCard,
  LucideDynamicIcon,
  LucideMapPin as MapPin,
  LucidePlus as Plus,
  LucideSearchX as SearchX,
  LucideZap as Zap,
} from '@lucide/angular';

import { API, errorCode, errorMessage } from '../../core/api/api';
import { CustomerApi } from '../../core/api/customer-api';
import { Address, CouponOffer, InstantQuote, InstantRequest, PaymentMethod } from '../../core/api/models';
import { LiveStream } from '../../core/live/live-stream';
import { ToastService } from '../../core/ui/feedback';
import { Dialog } from '../../shared/ui/dialog';
import { EmptyState, Skeleton, Spinner } from '../../shared/ui/kit';
import { AddressForm } from './address-form';
import { CouponPicker } from './coupon-picker';

const TIPS = [0, 20, 50, 100];
/** Fallback refresh of a search while the live stream is down (it moves every few seconds). */
export const SEARCH_POLL_MS = 4_000;

/**
 * /now/:serviceId — Genie Now (Phase 3C-9): the nearest free Genie, arriving within about an hour. The customer picks
 * the address, payment and tip, sees the price range of the Genies free nearby, and starts the search. Genies are
 * asked one at a time (45 s each, up to 5); this page follows the search live and opens the booking as soon as one
 * accepts, or offers a time slot instead when nobody is free.
 */
@Component({
  selector: 'pg-genie-now-page',
  imports: [RouterLink, CurrencyPipe, FormsModule, LucideDynamicIcon, AddressForm, CouponPicker, Dialog, EmptyState, Skeleton, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container-page max-w-3xl pt-6 pb-24 sm:pt-8">
      <a routerLink="/services" class="inline-flex items-center gap-1 text-sm font-semibold text-muted hover:text-brand">
        <svg [lucideIcon]="ArrowLeft" [size]="16" aria-hidden="true"></svg> Services
      </a>

      @if (search(); as s) {
        <!-- The search -->
        <section class="card mt-4 overflow-hidden" aria-live="polite" data-testid="instant-search">
          @if (s.status === 'SEARCHING') {
            <div class="grid place-items-center bg-navy px-5 py-10 text-center text-white">
              <span class="radar relative grid size-24 place-items-center rounded-full bg-gold/20" aria-hidden="true">
                <span class="grid size-14 place-items-center rounded-full bg-gold text-ink">
                  <svg [lucideIcon]="Zap" [size]="28"></svg>
                </span>
              </span>
              <h1 class="mt-6 text-2xl font-bold">Finding your Genie</h1>
              <p class="mt-2 text-brand-mist" data-testid="instant-message">{{ s.message }}</p>
              @if (secondsLeft() !== null) {
                <p class="mt-1 text-sm text-brand-soft">They have {{ secondsLeft() }} s to answer</p>
              }
              <div class="mt-5 flex gap-1.5" aria-hidden="true">
                @for (i of steps(s.maxGenies); track i) {
                  <span class="h-1.5 w-8 rounded-full" [class]="i <= s.geniesAsked ? 'bg-gold' : 'bg-white/20'"></span>
                }
              </div>
            </div>
            <div class="flex flex-col gap-3 p-5 sm:flex-row sm:items-center sm:justify-between">
              <p class="text-sm text-muted">Searching is free. You'll see the booking here as soon as a Genie accepts.</p>
              <button type="button" class="btn-ghost min-h-11" [disabled]="busy()" (click)="cancel(s)">Stop searching</button>
            </div>
          } @else if (s.status === 'NO_GENIE') {
            <div class="p-6 text-center sm:p-8">
              <span class="mx-auto grid size-14 place-items-center rounded-2xl bg-amber-50 text-amber-700">
                <svg [lucideIcon]="SearchX" [size]="28" aria-hidden="true"></svg>
              </span>
              <h1 class="mt-4 text-2xl font-bold">No Genie is free right now</h1>
              <p class="mt-2 text-muted" data-testid="instant-message">{{ s.message }}</p>
              <div class="mt-6 flex flex-col justify-center gap-2 sm:flex-row">
                <a routerLink="/services" class="btn-primary min-h-12">Book a time slot</a>
                <button type="button" class="btn-ghost min-h-12" (click)="reset()">Try again</button>
              </div>
            </div>
          } @else if (s.status === 'BOOKED') {
            <div class="grid place-items-center p-8 text-center">
              <pg-spinner />
              <p class="mt-3 font-semibold">{{ s.message }}</p>
            </div>
          }
        </section>
      } @else {
        <div class="mt-3 flex flex-wrap items-center gap-2">
          <span class="chip bg-gold py-1 font-semibold text-ink"><svg [lucideIcon]="Zap" [size]="14" aria-hidden="true"></svg> Genie Now</span>
        </div>
        <h1 class="mt-2 text-3xl font-bold sm:text-4xl">{{ quote()?.serviceName ?? 'Book a Genie now' }}</h1>
        <p class="mt-1 text-muted">
          The nearest free Genie comes to you, usually within {{ quote()?.arrivalMinutes ?? 60 }} minutes. Pay after the job.
        </p>

        <!-- Address -->
        <section class="card mt-6 p-5 sm:p-6" aria-labelledby="now-addr">
          <div class="flex items-center justify-between gap-3">
            <h2 id="now-addr" class="text-lg font-bold">Where?</h2>
            @if ((addresses.value() ?? []).length > 0) {
              <button type="button" class="btn-ghost min-h-10 px-4" (click)="addressOpen.set(true)">
                <svg [lucideIcon]="Plus" [size]="16" aria-hidden="true"></svg> New
              </button>
            }
          </div>
          <div class="mt-4 grid gap-3 sm:grid-cols-2">
            @for (a of addresses.value() ?? []; track a.id) {
              <button
                type="button"
                class="flex gap-3 rounded-2xl border p-4 text-left transition"
                [class]="a.id === addressId() ? 'border-brand bg-brand-mist/40 ring-2 ring-brand' : 'border-line bg-white hover:border-brand-soft'"
                [attr.aria-pressed]="a.id === addressId()"
                (click)="addressId.set(a.id)"
              >
                <svg [lucideIcon]="MapPin" [size]="18" class="mt-0.5 shrink-0 text-brand" aria-hidden="true"></svg>
                <span class="min-w-0">
                  <span class="block font-semibold">{{ a.label }}{{ a.isDefault ? ' · default' : '' }}</span>
                  <span class="block truncate text-sm text-muted">{{ a.line1 }}{{ a.area ? ', ' + a.area : '' }}</span>
                </span>
              </button>
            } @empty {
              @if (!addresses.isLoading()) {
                <div class="sm:col-span-2">
                  <pg-empty [icon]="MapPin" title="Add where the job is" message="Pin your home on the map so the nearest Genie can find you.">
                    <button type="button" class="btn-primary" (click)="addressOpen.set(true)">Add address</button>
                  </pg-empty>
                </div>
              }
            }
          </div>
        </section>

        <!-- Availability and price -->
        @if (addressId()) {
          <section class="card mt-6 p-5 sm:p-6" aria-live="polite" data-testid="instant-quote">
            @if (quote(); as q) {
              @if (q.available) {
                <p class="text-sm font-semibold text-emerald-700">{{ q.genieCount }} Genie{{ q.genieCount > 1 ? 's' : '' }} free near you</p>
                <p class="mt-1 font-display text-3xl font-bold">
                  {{ q.priceFrom | currency: 'INR' : 'symbol' : '1.0-0' }}{{ q.priceTo !== q.priceFrom ? ' – ' + (q.priceTo | currency: 'INR' : 'symbol' : '1.0-0') : '' }}
                </p>
                <p class="mt-1 text-sm text-muted">
                  Service, travel{{ q.instantFee > 0 ? ' and a ' + (q.instantFee | currency: 'INR' : 'symbol' : '1.0-0') + ' Genie Now fee' : '' }}.
                  The exact price is the price of the Genie who accepts, shown on your booking.
                </p>
              } @else {
                <p class="font-semibold">{{ q.reason }}</p>
                <a routerLink="/services" class="btn-primary mt-4 min-h-11">Book a time slot</a>
              }
            } @else if (quoteError()) {
              <p class="text-sm text-rose-700" role="alert">{{ quoteError() }}</p>
            } @else {
              <pg-skeleton [rows]="1" />
            }
          </section>
        }

        @if (quote()?.available) {
          <!-- Payment, tip, note, coupon -->
          <section class="card mt-6 p-5 sm:p-6" aria-labelledby="now-pay">
            <h2 id="now-pay" class="text-lg font-bold">Payment</h2>
            <div class="mt-4 grid gap-3 sm:grid-cols-2" role="radiogroup" aria-label="Payment method">
              @for (m of methods; track m.value) {
                <button
                  type="button"
                  role="radio"
                  class="flex gap-3 rounded-2xl border p-4 text-left transition"
                  [class]="m.value === method() ? 'border-brand bg-brand-mist/40 ring-2 ring-brand' : 'border-line bg-white hover:border-brand-soft'"
                  [attr.aria-checked]="m.value === method()"
                  (click)="method.set(m.value)"
                >
                  <svg [lucideIcon]="m.icon" [size]="20" class="mt-0.5 shrink-0 text-brand" aria-hidden="true"></svg>
                  <span>
                    <span class="block font-semibold">{{ m.label }}</span>
                    <span class="block text-sm text-muted">{{ m.hint }}</span>
                  </span>
                </button>
              }
            </div>
            <p class="label mt-6">Tip for your Genie <span class="font-normal text-muted">(optional, 100% to them)</span></p>
            <div class="grid grid-cols-4 gap-2 sm:max-w-sm">
              @for (t of tips; track t) {
                <button
                  type="button"
                  class="min-h-11 rounded-xl border text-sm font-semibold"
                  [class]="tip() === t ? 'border-brand bg-brand text-white' : 'border-line bg-white hover:border-brand'"
                  (click)="tip.set(t)"
                >
                  {{ t === 0 ? 'None' : '₹' + t }}
                </button>
              }
            </div>
            <label class="label mt-6" for="now-notes">Note for the Genie <span class="font-normal text-muted">(optional)</span></label>
            <textarea id="now-notes" class="field min-h-20" maxlength="500" placeholder="e.g. Ceiling fan in the bedroom, ladder available." [(ngModel)]="notes"></textarea>
            <p class="label mt-6">Coupon <span class="font-normal text-muted">(optional)</span></p>
            <pg-coupon-picker class="block" [serviceId]="serviceIdNum()" [addressId]="addressId()" [(applied)]="coupon" />
          </section>

          @if (error()) {
            <p class="mt-4 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ error() }}</p>
          }
          <p class="mt-4 text-xs text-muted">
            Up to {{ quote()!.maxGenies }} nearby Genies are asked, one at a time ({{ quote()!.offerSeconds }} seconds each). Stopping the
            search is free. Once a Genie accepts, the usual cancellation rules apply (a {{ 49 | currency: 'INR' : 'symbol' : '1.0-0' }} fee
            within 2 hours of the visit).
          </p>

          <div class="sticky bottom-0 z-30 -mx-4 mt-6 border-t border-line bg-white/95 px-4 py-3 backdrop-blur sm:static sm:mx-0 sm:border-0 sm:bg-transparent sm:p-0">
            <button type="button" class="btn-gold min-h-12 w-full" [disabled]="busy() || !addressId()" (click)="start()">
              @if (busy()) {
                <pg-spinner />
              } @else {
                <svg [lucideIcon]="Zap" [size]="18" aria-hidden="true"></svg>
              }
              Find a Genie now
            </button>
          </div>
        }
      }

      <pg-dialog [open]="addressOpen()" heading="New address" size="lg" (closed)="addressOpen.set(false)">
        @if (addressOpen()) {
          <pg-address-form (saved)="onAddressSaved($event)" (cancelled)="addressOpen.set(false)" />
        }
      </pg-dialog>
    </div>
  `,
  styles: `
    .radar::before,
    .radar::after {
      content: '';
      position: absolute;
      inset: 0;
      border-radius: 9999px;
      border: 2px solid rgb(245 179 1 / 0.6);
      animation: radar 2s ease-out infinite;
    }
    .radar::after {
      animation-delay: 1s;
    }
    @keyframes radar {
      from {
        transform: scale(0.6);
        opacity: 1;
      }
      to {
        transform: scale(1.8);
        opacity: 0;
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .radar::before,
      .radar::after {
        animation: none;
      }
    }
  `,
})
export default class GenieNowPage {
  readonly serviceId = input.required<string>();

  private readonly api = inject(CustomerApi);
  private readonly live = inject(LiveStream);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  protected readonly addresses = httpResource<Address[]>(() => `${API}/me/addresses`);
  protected readonly addressId = signal<string | null>(null);
  protected readonly quote = signal<InstantQuote | null>(null);
  protected readonly quoteError = signal<string | null>(null);
  protected readonly method = signal<PaymentMethod>('CASH');
  protected readonly tip = signal(0);
  protected notes = '';
  protected readonly coupon = signal<CouponOffer | null>(null);
  protected readonly addressOpen = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  /** The running (or just finished) search. */
  readonly search = signal<InstantRequest | null>(null);
  private readonly now = signal(Date.now());

  protected readonly serviceIdNum = computed(() => Number(this.serviceId()));
  protected readonly tips = TIPS;
  protected readonly methods = [
    { value: 'CASH' as const, label: 'Cash after the job', hint: 'Pay the Genie when the work is done.', icon: Banknote },
    { value: 'ONLINE' as const, label: 'Pay online after the job', hint: 'UPI or card in the app once it is done.', icon: CreditCard },
  ];
  protected readonly secondsLeft = computed(() => {
    const at = this.search()?.offerExpiresAt;
    const max = this.quote()?.offerSeconds ?? 45;
    return at ? Math.min(max, Math.max(0, Math.ceil((new Date(at).getTime() - this.now()) / 1000))) : null;
  });

  constructor() {
    // Preselect the default address; reload the quote whenever the address changes.
    effect(() => {
      const list = this.addresses.value();
      if (list?.length) untracked(() => this.addressId() ?? this.addressId.set((list.find((a) => a.isDefault) ?? list[0]).id));
    });
    effect(() => {
      const id = this.addressId();
      const service = this.serviceIdNum();
      if (id) untracked(() => this.loadQuote(service, id));
    });
    this.live
      .on('instant')
      .pipe(filter((r) => r.id === this.search()?.id), takeUntilDestroyed())
      .subscribe((r) => this.apply(r));
    const tick = setInterval(() => this.now.set(Date.now()), 1000);
    const poll = setInterval(() => {
      const s = this.search();
      if (s?.status === 'SEARCHING' && !this.live.connected()) this.refresh(s.id);
    }, SEARCH_POLL_MS);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(tick);
      clearInterval(poll);
    });
  }

  protected steps(n: number): number[] {
    return Array.from({ length: n }, (_, i) => i + 1);
  }

  private loadQuote(serviceId: number, addressId: string): void {
    this.quote.set(null);
    this.quoteError.set(null);
    this.api.instantQuote(serviceId, addressId).subscribe({
      next: (q) => this.quote.set(q),
      error: (err) => this.quoteError.set(errorMessage(err, 'Could not check this address.')),
    });
  }

  protected start(): void {
    const addressId = this.addressId();
    if (!addressId) return;
    this.busy.set(true);
    this.error.set(null);
    this.api
      .startInstant({
        serviceId: this.serviceIdNum(),
        addressId,
        paymentMethod: this.method(),
        tipAmount: this.tip(),
        notes: this.notes.trim() || null,
        couponCode: this.coupon()?.code ?? null,
      })
      .subscribe({
        next: (r) => {
          this.busy.set(false);
          this.apply(r);
        },
        error: (err) => {
          this.busy.set(false);
          this.error.set(errorCode(err) === 'OUTSTANDING_FEE' ? errorMessage(err) + ' (see My bookings)' : errorMessage(err));
        },
      });
  }

  protected cancel(s: InstantRequest): void {
    this.busy.set(true);
    this.api.cancelInstant(s.id).subscribe({
      next: (r) => {
        this.busy.set(false);
        if (r.status === 'CANCELLED') {
          this.toast.success('Search stopped');
          this.reset();
        } else {
          this.apply(r);
        }
      },
      error: (err) => {
        this.busy.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected reset(): void {
    this.search.set(null);
    const id = this.addressId();
    if (id) this.loadQuote(this.serviceIdNum(), id);
  }

  private refresh(id: string): void {
    this.api.instantRequest(id).subscribe({ next: (r) => this.apply(r), error: () => undefined });
  }

  private apply(r: InstantRequest): void {
    this.now.set(Date.now());
    this.search.set(r);
    if (r.status === 'BOOKED' && r.bookingId) {
      this.toast.success('A Genie accepted! They are on their way soon.');
      void this.router.navigate(['/account/bookings', r.bookingId]);
    }
  }

  protected onAddressSaved(a: Address): void {
    this.addressOpen.set(false);
    this.addresses.reload();
    this.addressId.set(a.id);
  }

  protected readonly ArrowLeft = ArrowLeft;
  protected readonly MapPin = MapPin;
  protected readonly Plus = Plus;
  protected readonly SearchX = SearchX;
  protected readonly Zap = Zap;
}
