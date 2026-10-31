import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, signal, untracked } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { Router } from '@angular/router';
import { LucideDynamicIcon, LucideMapPin as MapPin, LucideZap as Zap } from '@lucide/angular';

import { errorCode, errorMessage } from '../../core/api/api';
import { GenieApi } from '../../core/api/genie-api';
import { InstantOffer } from '../../core/api/models';
import { LiveStream } from '../../core/live/live-stream';
import { ToastService } from '../../core/ui/feedback';
import { Spinner } from '../../shared/ui/kit';
import { GenieStore } from './genie-store';

/** While the live stream is down, open offers are fetched this often (an offer lasts 45 s). */
export const OFFER_POLL_MS = 10_000;

/**
 * Genie Now offers (Phase 3C-9), shown on every Genie console page: a card with the job, what the Genie earns and a
 * countdown, to accept or pass. Offers arrive over the live stream ({@code instant_offer}); while it is down they are
 * fetched every few seconds. Also tells the Genie when they were switched offline because ProGenie was closed.
 */
@Component({
  selector: 'pg-instant-offer-panel',
  imports: [CurrencyPipe, DecimalPipe, LucideDynamicIcon, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (offer(); as o) {
      <section
        class="fixed inset-x-3 bottom-3 z-[1100] mx-auto max-w-md overflow-hidden rounded-3xl bg-white shadow-2xl ring-1 ring-line sm:inset-x-auto sm:right-6 sm:bottom-6"
        role="alertdialog"
        aria-labelledby="offer-h"
        aria-describedby="offer-d"
        data-testid="instant-offer"
      >
        <div class="bg-navy p-5 text-white">
          <p class="flex items-center gap-2 text-sm font-semibold text-gold">
            <svg [lucideIcon]="Zap" [size]="16" aria-hidden="true"></svg> Genie Now job
          </p>
          <h2 id="offer-h" class="mt-1 text-xl font-bold">{{ o.serviceName }}</h2>
          <p id="offer-d" class="mt-1 flex items-center gap-1 text-sm text-brand-mist">
            <svg [lucideIcon]="MapPin" [size]="14" aria-hidden="true"></svg>
            {{ o.area ? o.area + ', ' : '' }}{{ o.city }} · {{ o.distanceKm | number: '1.1-1' }} km{{ o.travelMinutes ? ' (~' + o.travelMinutes + ' min)' : '' }} · ~{{ o.durationMinutes }} min job
          </p>
        </div>
        <div class="p-5">
          <div class="flex items-end justify-between gap-3">
            <div>
              <p class="text-xs font-semibold uppercase tracking-wider text-muted">You earn</p>
              <p class="font-display text-3xl font-bold text-emerald-700">{{ o.earning | currency: 'INR' }}</p>
              <p class="text-xs text-muted">{{ o.paymentMethod === 'CASH' ? 'Cash from the customer' : 'Paid online, into your wallet' }} · arrive within an hour</p>
            </div>
            <p class="text-right font-display text-2xl font-bold tabular-nums" [class.text-rose-700]="secondsLeft() <= 10" aria-live="off">
              {{ secondsLeft() }}s
            </p>
          </div>
          <div class="mt-3 h-1.5 overflow-hidden rounded-full bg-surface" aria-hidden="true">
            <div class="h-full rounded-full bg-gold transition-[width] duration-1000 ease-linear" [style.width.%]="percentLeft()"></div>
          </div>
          @if (o.notes) {
            <p class="mt-3 rounded-xl bg-surface px-3 py-2 text-sm">"{{ o.notes }}"</p>
          }
          <div class="mt-4 grid grid-cols-[1fr_2fr] gap-2">
            <button type="button" class="btn-ghost min-h-12" [disabled]="busy()" (click)="decline(o)">Pass</button>
            <button type="button" class="btn-gold min-h-12" [disabled]="busy()" (click)="accept(o)">
              @if (busy()) {
                <pg-spinner />
              }
              Accept job
            </button>
          </div>
        </div>
      </section>
    }
  `,
})
export class InstantOfferPanel {
  private readonly api = inject(GenieApi);
  private readonly live = inject(LiveStream);
  private readonly store = inject(GenieStore);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  readonly offers = signal<InstantOffer[]>([]);
  protected readonly busy = signal(false);
  private readonly now = signal(Date.now());

  /** The offer on screen: the open one that expires first. */
  readonly offer = computed(() => this.offers().find((o) => new Date(o.expiresAt).getTime() > this.now()) ?? null);
  protected readonly secondsLeft = computed(() => {
    const o = this.offer();
    // clamped to the offer length: the phone's clock may be a little behind the server's
    return o ? Math.min(45, Math.max(0, Math.ceil((new Date(o.expiresAt).getTime() - this.now()) / 1000))) : 0;
  });
  protected readonly percentLeft = computed(() => Math.min(100, (this.secondsLeft() / 45) * 100));

  constructor() {
    this.live
      .on('instant_offer')
      .pipe(takeUntilDestroyed())
      .subscribe((e) => {
        if ('serviceName' in e) {
          this.offers.update((list) => [...list.filter((o) => o.id !== e.id), e]);
          navigator.vibrate?.([200, 100, 200]);
        } else {
          this.offers.update((list) => list.filter((o) => o.id !== e.offerId));
        }
      });
    this.live
      .on('presence')
      .pipe(takeUntilDestroyed())
      .subscribe((e) => {
        const p = this.store.profile();
        if (!e.online && p) {
          this.store.set({ ...p, online: false });
          this.toast.error('You were switched offline because ProGenie was closed. Go online again to get Genie Now jobs.');
        }
      });
    // Catch up whenever the stream (re)connects, e.g. after the phone woke up.
    effect(() => {
      if (this.live.connected() && this.store.approved()) untracked(() => this.load());
    });
    const tick = setInterval(() => this.now.set(Date.now()), 1000);
    const poll = setInterval(() => {
      if (this.store.profile()?.online && !this.live.connected()) this.load();
    }, OFFER_POLL_MS);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(tick);
      clearInterval(poll);
    });
  }

  load(): void {
    this.api.instantOffers().subscribe({ next: (list) => this.offers.set(list), error: () => undefined });
  }

  protected accept(o: InstantOffer): void {
    this.busy.set(true);
    this.api.acceptOffer(o.id).subscribe({
      next: (b) => {
        this.busy.set(false);
        this.drop(o.id);
        this.store.refreshRequests();
        this.toast.success('Job accepted. Tap "Start travelling" when you leave.');
        void this.router.navigate(['/genie/bookings', b.id]);
      },
      error: (err) => {
        this.busy.set(false);
        this.drop(o.id);
        this.toast.error(errorCode(err) === 'OFFER_GONE' ? 'Too late: this job is no longer available.' : errorMessage(err));
      },
    });
  }

  protected decline(o: InstantOffer): void {
    this.drop(o.id);
    this.api.declineOffer(o.id).subscribe({ error: () => undefined });
  }

  private drop(id: number): void {
    this.offers.update((list) => list.filter((x) => x.id !== id));
  }

  protected readonly MapPin = MapPin;
  protected readonly Zap = Zap;
}
