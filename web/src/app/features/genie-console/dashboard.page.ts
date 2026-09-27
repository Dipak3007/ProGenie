import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import {
  LucideBriefcase as Briefcase,
  LucideCircleAlert as CircleAlert,
  LucideDynamicIcon,
  LucideHourglass as Hourglass,
  LucideIndianRupee as Rupee,
  LucideShieldCheck as ShieldCheck,
  LucideStar as Star,
  LucideTrendingUp as TrendingUp,
} from '@lucide/angular';

import { API, errorMessage, query } from '../../core/api/api';
import { GenieApi } from '../../core/api/genie-api';
import { BookingSummary, GenieStats, Page, Wallet } from '../../core/api/models';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { inr } from '../../shared/format';
import { BookingRow } from '../../shared/ui/booking-parts';
import { ChartPoint, ColumnChart } from '../../shared/ui/charts';
import { EmptyState, PageHead, Spinner, StatTile } from '../../shared/ui/kit';
import { StatusBadge } from '../../shared/ui/status-badge';
import { ALL_STEPS, GenieStore, STEP_TEXT } from './genie-store';

@Component({
  selector: 'pg-genie-dashboard-page',
  imports: [RouterLink, DecimalPipe, LucideDynamicIcon, BookingRow, ColumnChart, EmptyState, PageHead, Spinner, StatTile, StatusBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (store.profile(); as p) {
      <pg-page-head [title]="'Namaste, ' + p.fullName.split(' ')[0]" [subtitle]="subtitle()">
        <pg-status [status]="p.verificationStatus" />
      </pg-page-head>

      @if (p.verificationStatus !== 'APPROVED') {
        <!-- Onboarding / verification state -->
        <section class="card mt-6 overflow-hidden">
          <div class="lamp-glow p-5 text-white sm:p-7">
            @switch (p.verificationStatus) {
              @case ('UNDER_REVIEW') {
                <p class="flex items-center gap-2 font-semibold text-gold"><svg [lucideIcon]="Hourglass" [size]="18" aria-hidden="true"></svg> Under review</p>
                <h2 class="mt-2 text-2xl font-bold sm:text-3xl">Thanks! Our team is checking your documents.</h2>
                <p class="mt-2 max-w-2xl text-brand-mist">This usually takes 24–48 hours. You'll get a notification as soon as you're approved.</p>
              }
              @case ('NEEDS_CHANGES') {
                <p class="flex items-center gap-2 font-semibold text-gold"><svg [lucideIcon]="CircleAlert" [size]="18" aria-hidden="true"></svg> A few changes needed</p>
                <h2 class="mt-2 text-2xl font-bold sm:text-3xl">Please update your profile and submit again.</h2>
                @if (p.verificationNote) {
                  <p class="mt-3 max-w-2xl rounded-xl bg-white/10 px-4 py-3 text-brand-mist">“{{ p.verificationNote }}”</p>
                }
              }
              @case ('REJECTED') {
                <h2 class="text-2xl font-bold sm:text-3xl">We couldn't approve your profile.</h2>
                @if (p.verificationNote) {
                  <p class="mt-3 max-w-2xl rounded-xl bg-white/10 px-4 py-3 text-brand-mist">“{{ p.verificationNote }}”</p>
                }
                <p class="mt-2 text-brand-mist">Questions? Write to us from the Contact section on the home page.</p>
              }
              @case ('SUSPENDED') {
                <h2 class="text-2xl font-bold sm:text-3xl">Your account is paused.</h2>
                <p class="mt-2 max-w-2xl text-brand-mist">You won't receive bookings for now. {{ p.verificationNote }}</p>
              }
              @default {
                <p class="flex items-center gap-2 font-semibold text-gold"><svg [lucideIcon]="ShieldCheck" [size]="18" aria-hidden="true"></svg> Get verified</p>
                <h2 class="mt-2 text-2xl font-bold sm:text-3xl">Finish your profile to start getting bookings.</h2>
                <p class="mt-2 max-w-2xl text-brand-mist">Customers only see admin-verified Genies. It takes about 10 minutes.</p>
              }
            }
            @if (store.editable()) {
              <div class="mt-5">
                <div class="flex items-center justify-between text-sm text-brand-mist">
                  <span>Profile {{ store.progress() }}% complete</span><span>{{ 7 - p.missingSteps.length }}/7 steps</span>
                </div>
                <div class="mt-2 h-2 rounded-full bg-white/15"><div class="h-2 rounded-full bg-gold transition-all" [style.width.%]="store.progress()"></div></div>
              </div>
            }
          </div>
          @if (store.editable()) {
            <div class="grid gap-2 p-5 sm:grid-cols-2 sm:p-6 lg:grid-cols-3">
              @for (step of steps; track step) {
                <a
                  routerLink="/genie/onboarding"
                  [fragment]="text[step].section"
                  class="flex items-start gap-3 rounded-2xl border p-3 transition hover:border-brand-soft"
                  [class]="p.missingSteps.includes(step) ? 'border-line bg-white' : 'border-emerald-200 bg-emerald-50/60'"
                >
                  <span
                    class="mt-0.5 grid size-6 shrink-0 place-items-center rounded-full text-xs font-bold"
                    [class]="p.missingSteps.includes(step) ? 'bg-surface text-muted' : 'bg-emerald-600 text-white'"
                    >{{ p.missingSteps.includes(step) ? '' : '✓' }}</span
                  >
                  <span>
                    <span class="block text-sm font-semibold">{{ text[step].title }}</span>
                    <span class="block text-xs text-muted">{{ text[step].hint }}</span>
                  </span>
                </a>
              }
            </div>
            <div class="flex flex-col gap-3 border-t border-line p-5 sm:flex-row sm:items-center sm:justify-between sm:p-6">
              <p class="text-sm text-muted">
                {{ p.canSubmit ? 'Everything is in place. Send your profile to our team.' : 'Complete the remaining steps, then submit for review.' }}
              </p>
              <a routerLink="/genie/onboarding" class="btn-gold">{{ p.canSubmit ? 'Review and submit' : 'Continue setup' }}</a>
            </div>
          }
        </section>
      } @else {
        <!-- Online toggle -->
        <section class="card mt-6 flex flex-col gap-4 p-5 sm:flex-row sm:items-center sm:p-6" [class.ring-2]="p.online" [class.ring-emerald-400]="p.online">
          <span class="relative grid size-12 shrink-0 place-items-center rounded-2xl" [class]="p.online ? 'bg-emerald-100 text-emerald-700' : 'bg-surface text-muted'">
            <span class="size-3 rounded-full" [class]="p.online ? 'bg-emerald-500' : 'bg-slate-400'"></span>
            @if (p.online) {
              <span class="absolute size-3 animate-ping rounded-full bg-emerald-400"></span>
            }
          </span>
          <div class="min-w-0 flex-1">
            <p class="text-lg font-bold">{{ p.online ? "You're online" : "You're offline" }}</p>
            <p class="text-sm text-muted">
              {{ p.online ? 'Customers see "Online now" on your card. Bookings still follow your working hours.' : 'Go online to show customers you are available right now.' }}
            </p>
          </div>
          <button
            type="button"
            role="switch"
            [attr.aria-checked]="p.online"
            class="btn min-h-12 px-6"
            [class]="p.online ? 'border border-line bg-white text-ink hover:border-rose-300 hover:text-rose-700' : 'bg-emerald-600 text-white hover:bg-emerald-700'"
            [disabled]="toggling()"
            (click)="toggleOnline(!p.online)"
          >
            @if (toggling()) {
              <pg-spinner />
            }
            {{ p.online ? 'Go offline' : 'Go online' }}
          </button>
        </section>

        <!-- KPIs -->
        <div class="mt-6 grid grid-cols-2 gap-3 sm:gap-4 xl:grid-cols-4">
          <pg-stat [dark]="true" label="This week" [value]="money(wallet.value()?.earningsThisWeek)" [hint]="(wallet.value()?.jobsThisWeek ?? 0) + ' jobs'" [icon]="Rupee" />
          <pg-stat label="Wallet" [value]="money(wallet.value()?.balance)" [hint]="walletHint()" [icon]="TrendingUp" />
          <pg-stat label="Rating" [value]="p.ratingCount ? (p.avgRating | number: '1.1-1') + '★' : 'New'" [hint]="p.ratingCount + ' reviews'" [icon]="Star" />
          <pg-stat label="Acceptance" [value]="stats.value()?.acceptanceRate != null ? stats.value()!.acceptanceRate + '%' : '—'" hint="Last 30 days" [icon]="Briefcase" />
        </div>

        <div class="mt-6 grid gap-6 xl:grid-cols-2">
          <!-- Requests -->
          <section aria-labelledby="req-h">
            <div class="flex items-center justify-between gap-3">
              <h2 id="req-h" class="text-lg font-bold">New requests</h2>
              <a routerLink="/genie/jobs" class="text-sm font-semibold text-brand hover:underline">All jobs</a>
            </div>
            <div class="mt-3 space-y-3">
              @for (b of requests.value()?.items ?? []; track b.id) {
                <div class="card p-4">
                  <pg-booking-row class="[&_.card]:border-0 [&_.card]:p-0 [&_.card]:shadow-none" [booking]="b" linkBase="/genie/bookings" audience="GENIE" counterpartLabel="for" />
                  <div class="mt-3 flex gap-2 border-t border-line pt-3">
                    <button type="button" class="btn-primary flex-1" [disabled]="busyId() === b.id" (click)="accept(b)">Accept</button>
                    <button type="button" class="btn-ghost flex-1" [disabled]="busyId() === b.id" (click)="decline(b)">Decline</button>
                  </div>
                </div>
              } @empty {
                <pg-empty [icon]="Briefcase" title="No new requests" message="New bookings appear here. Reply within 30 minutes or they expire." />
              }
            </div>
          </section>

          <!-- Upcoming -->
          <section aria-labelledby="up-h">
            <h2 id="up-h" class="text-lg font-bold">Upcoming jobs</h2>
            <div class="mt-3 space-y-3">
              @for (b of upcoming.value()?.items ?? []; track b.id) {
                <pg-booking-row [booking]="b" linkBase="/genie/bookings" audience="GENIE" counterpartLabel="for" />
              } @empty {
                <pg-empty title="Nothing scheduled" message="Accepted jobs show up here with the customer's address." />
              }
            </div>
          </section>
        </div>

        @if (chart().length) {
          <section class="card mt-6 p-5 sm:p-6" aria-labelledby="chart-h">
            <div class="flex flex-wrap items-baseline justify-between gap-2">
              <h2 id="chart-h" class="text-lg font-bold">Completed jobs per day</h2>
              <p class="text-sm text-muted">Last 30 days · {{ stats.value()?.completed ?? 0 }} jobs · {{ money(stats.value()?.earnings) }} earned</p>
            </div>
            <pg-column-chart class="mt-4" [points]="chart()" title="Completed jobs" />
          </section>
        }
      }
    } @else {
      <div class="card h-40 animate-pulse"></div>
    }
  `,
})
export default class GenieDashboardPage {
  protected readonly store = inject(GenieStore);
  private readonly api = inject(GenieApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);

  private readonly live = computed(() => this.store.approved());
  protected readonly wallet = httpResource<Wallet>(() => (this.live() ? `${API}/genie/wallet` : undefined));
  protected readonly stats = httpResource<GenieStats>(() => (this.live() ? `${API}/genie/analytics?days=30` : undefined));
  protected readonly requests = httpResource<Page<BookingSummary>>(() => (this.live() ? `${API}/genie/bookings${query({ scope: 'REQUESTS', size: 5 })}` : undefined));
  protected readonly upcoming = httpResource<Page<BookingSummary>>(() => (this.live() ? `${API}/genie/bookings${query({ scope: 'UPCOMING', size: 5 })}` : undefined));

  protected readonly toggling = signal(false);
  protected readonly busyId = signal<string | null>(null);
  protected readonly steps = ALL_STEPS;
  protected readonly text = STEP_TEXT;
  protected readonly money = inr;

  protected readonly chart = computed<ChartPoint[]>(() => (this.stats.value()?.daily ?? []).map((d) => ({ label: d.day, value: d.completed })));
  protected readonly subtitle = computed(() =>
    this.store.approved() ? `${this.store.requests() || 'No'} new request${this.store.requests() === 1 ? '' : 's'} waiting` : 'Your Genie console',
  );
  protected readonly walletHint = computed(() => {
    const w = this.wallet.value();
    if (!w) return null;
    return w.commissionDue > 0 ? `${inr(w.commissionDue)} commission due` : `${inr(w.availableForPayout)} for next payout`;
  });

  protected toggleOnline(online: boolean): void {
    this.toggling.set(true);
    this.api.setOnline(online).subscribe({
      next: (res) => {
        this.toggling.set(false);
        const p = this.store.profile();
        if (p) this.store.set({ ...p, online: res.online });
        this.toast.success(res.online ? "You're online" : "You're offline");
        if (res.online) this.shareLocation();
      },
      error: (err) => {
        this.toggling.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  /** Best effort: send the live location once when going online (used for instant jobs later). */
  private shareLocation(): void {
    if (!('geolocation' in navigator)) return;
    navigator.geolocation.getCurrentPosition(
      (pos) => this.api.updateLocation(pos.coords.latitude, pos.coords.longitude, Math.round(pos.coords.accuracy)).subscribe({ error: () => undefined }),
      () => undefined,
      { timeout: 8000 },
    );
  }

  protected accept(b: BookingSummary): void {
    this.busyId.set(b.id);
    this.api.accept(b.id).subscribe({
      next: () => {
        this.busyId.set(null);
        this.toast.success(`Accepted ${b.bookingRef}. The customer has been notified.`);
        this.reloadLists();
      },
      error: (err) => {
        this.busyId.set(null);
        this.toast.error(errorMessage(err));
        this.reloadLists();
      },
    });
  }

  protected async decline(b: BookingSummary): Promise<void> {
    const { confirmed, value } = await this.confirm.ask({
      title: `Decline ${b.serviceName}?`,
      message: 'The customer will be told you are not available and can book someone else.',
      confirmLabel: 'Decline',
      tone: 'danger',
      input: { label: 'Reason for the customer', placeholder: 'e.g. Fully booked at that time', required: true },
    });
    if (!confirmed) return;
    this.busyId.set(b.id);
    this.api.decline(b.id, value).subscribe({
      next: () => {
        this.busyId.set(null);
        this.toast.success('Request declined');
        this.reloadLists();
      },
      error: (err) => {
        this.busyId.set(null);
        this.toast.error(errorMessage(err));
      },
    });
  }

  private reloadLists(): void {
    this.requests.reload();
    this.upcoming.reload();
    this.store.refreshRequests();
  }

  protected readonly Briefcase = Briefcase;
  protected readonly CircleAlert = CircleAlert;
  protected readonly Hourglass = Hourglass;
  protected readonly Rupee = Rupee;
  protected readonly ShieldCheck = ShieldCheck;
  protected readonly Star = Star;
  protected readonly TrendingUp = TrendingUp;
}
