import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import {
  LucideBadgeCheck as BadgeCheck,
  LucideClock as Clock,
  LucideDynamicIcon,
  LucideHeart as Heart,
  LucideMapPin as MapPin,
} from '@lucide/angular';

import { API, ApiClient, errorMessage } from '../../core/api/api';
import { CustomerApi } from '../../core/api/customer-api';
import { GenieCard as GenieCardModel, GenieDetail, GenieService, PriceBreakdown } from '../../core/api/models';
import { AuthStore } from '../../core/auth/auth-store';
import { ToastService } from '../../core/ui/feedback';
import { Avatar } from '../../shared/ui/avatar';
import { CategoryVisual } from '../../shared/ui/category-visual';

/** Ahmedabad areas for the price estimate until customers have saved addresses. */
const AREAS = [
  { name: 'Navrangpura', lat: 23.0365, lng: 72.5611 },
  { name: 'Satellite', lat: 23.03, lng: 72.517 },
  { name: 'Bopal', lat: 23.0333, lng: 72.4639 },
  { name: 'Maninagar', lat: 22.9962, lng: 72.6031 },
  { name: 'Vastrapur', lat: 23.0395, lng: 72.529 },
  { name: 'Prahlad Nagar', lat: 23.012, lng: 72.5108 },
  { name: 'Gota', lat: 23.103, lng: 72.541 },
  { name: 'Chandkheda', lat: 23.109, lng: 72.585 },
  { name: 'Thaltej', lat: 23.049, lng: 72.508 },
  { name: 'Nikol', lat: 23.047, lng: 72.669 },
];

@Component({
  selector: 'pg-genie-profile-page',
  imports: [RouterLink, CurrencyPipe, DatePipe, DecimalPipe, LucideDynamicIcon, Avatar, CategoryVisual],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (detail.value(); as d) {
      <section class="relative h-56 overflow-hidden sm:h-72">
        <pg-category-visual [imageUrl]="d.genie.coverImage" [slug]="d.genie.categorySlug" [alt]="d.genie.categories" />
        <div class="absolute inset-0 bg-linear-to-t from-navy/90 via-navy/40 to-navy/10"></div>
      </section>
      <div class="container-page relative">
        <div class="card -mt-20 flex flex-col gap-5 p-6 sm:-mt-24 sm:flex-row sm:items-end sm:p-8">
          <pg-avatar class="-mt-16 sm:-mt-20" [name]="d.genie.fullName" [size]="112" [online]="d.genie.online" />
          <div class="min-w-0 flex-1">
            <h1 class="flex flex-wrap items-center gap-2 text-3xl font-extrabold sm:text-4xl">
              {{ d.genie.fullName }}
              <svg [lucideIcon]="BadgeCheck" [size]="26" class="text-brand" title="Verified Genie"></svg>
              @if (auth.role() === 'CUSTOMER') {
                <button
                  type="button"
                  class="ml-1 grid size-10 place-items-center rounded-full border border-line bg-white transition hover:border-rose-300"
                  [attr.aria-pressed]="isFavourite()"
                  [attr.aria-label]="isFavourite() ? 'Remove from favourites' : 'Save to favourites'"
                  (click)="toggleFavourite(d.genie.id)"
                >
                  <svg [lucideIcon]="Heart" [size]="18" [class]="isFavourite() ? 'fill-rose-600 text-rose-600' : 'text-muted'" aria-hidden="true"></svg>
                </button>
              }
            </h1>
            <p class="mt-1 text-muted">{{ d.genie.categories }} · {{ d.genie.experienceYears }} years experience</p>
          </div>
          <dl class="grid grid-cols-3 gap-3 text-center sm:gap-6">
            <div class="rounded-2xl bg-surface px-3 py-2">
              <dt class="text-xs text-muted">Rating</dt>
              <dd class="font-display text-xl font-bold">{{ d.genie.ratingCount ? (d.genie.avgRating | number: '1.1-1') : 'New' }}</dd>
            </div>
            <div class="rounded-2xl bg-surface px-3 py-2">
              <dt class="text-xs text-muted">Jobs</dt>
              <dd class="font-display text-xl font-bold">{{ d.genie.completedJobs }}</dd>
            </div>
            <div class="rounded-2xl bg-surface px-3 py-2">
              <dt class="text-xs text-muted">Area</dt>
              <dd class="truncate font-display text-base font-bold leading-7">{{ d.genie.baseArea ?? '—' }}</dd>
            </div>
          </dl>
        </div>
      </div>

      <div class="container-page grid gap-8 pt-12 pb-20 lg:grid-cols-[2fr_1fr]">
        <div class="space-y-8">
          @if (d.genie.bio) {
            <section class="card p-6">
              <h2 class="text-xl font-bold">About</h2>
              <p class="mt-2 text-ink/80">{{ d.genie.bio }}</p>
            </section>
          }

          <section aria-labelledby="svc-h">
            <h2 id="svc-h" class="text-2xl font-bold">Services &amp; prices</h2>
            <ul class="mt-4 grid gap-3 sm:grid-cols-2">
              @for (s of d.services; track s.serviceId) {
                <li>
                  <button
                    type="button"
                    class="card flex w-full items-start justify-between gap-3 p-4 text-left transition hover:border-brand-soft"
                    [class.ring-2]="selected()?.serviceId === s.serviceId"
                    [class.ring-brand]="selected()?.serviceId === s.serviceId"
                    [attr.aria-pressed]="selected()?.serviceId === s.serviceId"
                    (click)="select(s)"
                  >
                    <span>
                      <span class="block font-semibold">{{ s.name }}</span>
                      <span class="mt-1 inline-flex items-center gap-1 text-xs text-muted">
                        <svg [lucideIcon]="Clock" [size]="14" aria-hidden="true"></svg> ~{{ s.durationMinutes }} min
                      </span>
                    </span>
                    <span class="font-display text-lg font-bold">{{ s.price | currency: 'INR' : 'symbol' : '1.0-0' }}</span>
                  </button>
                </li>
              }
            </ul>
          </section>

          <section aria-labelledby="rev-h">
            <h2 id="rev-h" class="text-2xl font-bold">Reviews</h2>
            <ul class="mt-4 grid gap-3">
              @for (r of d.reviews; track $index) {
                <li class="card p-5">
                  <div class="flex items-center justify-between gap-3">
                    <p class="font-semibold">{{ r.customerName }}</p>
                    <p class="text-xs text-muted">{{ r.createdAt | date: 'mediumDate' }}</p>
                  </div>
                  <p class="mt-1 text-gold" [attr.aria-label]="r.rating + ' out of 5'">{{ stars(r.rating) }}</p>
                  @if (r.comment) {
                    <p class="mt-2 text-sm text-ink/80">{{ r.comment }}</p>
                  }
                  @if (r.genieReply) {
                    <div class="mt-3 rounded-xl bg-surface px-4 py-3 text-sm">
                      <p class="text-xs font-semibold text-brand-600">Reply from {{ d.genie.fullName.split(' ')[0] }}</p>
                      <p class="mt-1 text-ink/80">{{ r.genieReply }}</p>
                    </div>
                  }
                </li>
              } @empty {
                <li class="card p-5 text-sm text-muted">No reviews yet.</li>
              }
            </ul>
          </section>
        </div>

        <!-- Sticky price box -->
        <aside class="lg:sticky lg:top-24 lg:self-start">
          <div class="card p-6">
            <h2 class="text-xl font-bold">Price estimate</h2>
            @if (selected(); as s) {
              <p class="mt-1 text-sm text-muted">{{ s.name }}</p>
              <label class="label mt-4" for="area">Your area</label>
              <select id="area" class="field" [value]="areaIndex()" (change)="areaIndex.set(+$any($event.target).value); estimate()">
                @for (a of areas; track a.name; let i = $index) {
                  <option [value]="i">{{ a.name }}</option>
                }
              </select>

              @if (quote(); as q) {
                <dl class="mt-5 space-y-2 text-sm">
                  <div class="flex justify-between"><dt class="text-muted">Service</dt><dd>{{ q.serviceAmount | currency: 'INR' }}</dd></div>
                  <div class="flex justify-between">
                    <dt class="text-muted">Travel ({{ q.distanceKm | number: '1.1-1' }} km)</dt>
                    <dd>{{ q.travelFee > 0 ? (q.travelFee | currency: 'INR') : 'Free' }}</dd>
                  </div>
                  <div class="flex justify-between border-t border-line pt-2 text-base font-semibold">
                    <dt>Total</dt><dd>{{ q.totalAmount | currency: 'INR' }}</dd>
                  </div>
                </dl>
              } @else if (quoteError()) {
                <p class="mt-4 text-sm text-red-700">{{ quoteError() }}</p>
              }

              @if (auth.role() === null || auth.role() === 'CUSTOMER') {
                <button type="button" class="btn-gold mt-6 min-h-12 w-full" (click)="book()">
                  {{ auth.isLoggedIn() ? 'Choose a time' : 'Log in to book' }}
                </button>
                <p class="mt-3 text-xs text-muted">Pick a slot next. Nothing is charged until the job is done.</p>
              } @else {
                <p class="mt-6 rounded-xl bg-surface px-4 py-3 text-sm text-muted">Bookings are made from a customer account.</p>
              }
            } @else {
              <p class="mt-2 text-sm text-muted">Pick a service to see the full price including travel.</p>
            }
          </div>
        </aside>
      </div>
    } @else if (detail.error()) {
      <div class="container-page py-24 text-center">
        <h1 class="text-3xl font-bold">Genie not found</h1>
        <a routerLink="/services" class="btn-primary mt-6">Browse services</a>
      </div>
    } @else {
      <div class="container-page py-24"><div class="card h-48 animate-pulse"></div></div>
    }
  `,
})
export default class GenieProfilePage {
  readonly id = input.required<string>();

  protected readonly auth = inject(AuthStore);
  private readonly api = inject(ApiClient);
  private readonly customer = inject(CustomerApi);
  private readonly toast = inject(ToastService);
  private readonly router = inject(Router);

  /** Only customers have favourites; for everyone else the request is skipped. */
  private readonly favourites = httpResource<GenieCardModel[]>(() => (this.auth.role() === 'CUSTOMER' ? `${API}/me/favourites` : undefined));
  private readonly favOverride = signal<boolean | null>(null);
  protected readonly isFavourite = computed(() => this.favOverride() ?? (this.favourites.value() ?? []).some((g) => g.id === this.id()));

  protected readonly detail = httpResource<GenieDetail>(() => `${API}/genies/${encodeURIComponent(this.id())}`);
  protected readonly selected = signal<GenieService | null>(null);
  protected readonly areaIndex = signal(0);
  protected readonly quote = signal<PriceBreakdown | null>(null);
  protected readonly quoteError = signal<string | null>(null);
  protected readonly areas = AREAS;
  protected readonly initials = computed(() =>
    (this.detail.value()?.genie.fullName ?? '')
      .split(' ')
      .map((p) => p[0])
      .slice(0, 2)
      .join(''),
  );

  protected readonly BadgeCheck = BadgeCheck;
  protected readonly MapPin = MapPin;
  protected readonly Clock = Clock;

  protected stars(n: number): string {
    return '★'.repeat(n) + '☆'.repeat(5 - n);
  }

  protected select(service: GenieService): void {
    this.selected.set(service);
    this.estimate();
  }

  protected estimate(): void {
    const service = this.selected();
    if (!service) return;
    const area = AREAS[this.areaIndex()];
    this.quoteError.set(null);
    this.api.estimate(this.id(), service.serviceId, area.lat, area.lng).subscribe({
      next: (q) => this.quote.set(q),
      error: (err) => {
        this.quote.set(null);
        this.quoteError.set(errorMessage(err));
      },
    });
  }

  /** Guests can browse and price freely; logging in is required only to book. */
  protected book(): void {
    const serviceId = this.selected()?.serviceId;
    const target = this.router.createUrlTree(['/book', this.id()], { queryParams: serviceId ? { serviceId } : {} });
    if (!this.auth.isLoggedIn()) {
      this.router.navigate(['/login'], { queryParams: { returnUrl: this.router.serializeUrl(target) } });
      return;
    }
    this.router.navigateByUrl(target);
  }

  protected toggleFavourite(genieId: string): void {
    const next = !this.isFavourite();
    this.favOverride.set(next);
    const call = next ? this.customer.addFavourite(genieId) : this.customer.removeFavourite(genieId);
    call.subscribe({
      next: () => this.toast.success(next ? 'Saved to your favourites' : 'Removed from favourites'),
      error: (err) => {
        this.favOverride.set(!next);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected readonly Heart = Heart;
}
