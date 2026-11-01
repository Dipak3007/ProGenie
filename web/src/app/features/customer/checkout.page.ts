import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal, untracked, viewChild } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { CurrencyPipe, DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import {
  LucideArrowLeft as ArrowLeft,
  LucideBanknote as Banknote,
  LucideCircleCheck as CircleCheck,
  LucideClock as Clock,
  LucideCreditCard as CreditCard,
  LucideDynamicIcon,
  LucideMapPin as MapPin,
  LucideMinus as Minus,
  LucidePlus as Plus,
  LucideShieldCheck as ShieldCheck,
} from '@lucide/angular';
import { debounceTime, of, switchMap, catchError } from 'rxjs';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';

import { API, ApiClient, errorCode, errorMessage } from '../../core/api/api';
import { CustomerApi } from '../../core/api/customer-api';
import { Address, CouponOffer, GenieDetail, PaymentMethod, PriceBreakdown, Slot } from '../../core/api/models';
import { ToastService } from '../../core/ui/feedback';
import { newIdempotencyKey } from '../../shared/format';
import { Avatar } from '../../shared/ui/avatar';
import { Dialog } from '../../shared/ui/dialog';
import { EmptyState, Skeleton, Spinner } from '../../shared/ui/kit';
import { Rating } from '../../shared/ui/rating';
import { SlotPicker } from '../../shared/ui/slot-picker';
import { AddressForm } from './address-form';
import { CouponPicker } from './coupon-picker';
import {
  durationText,
  itemsParam,
  MAX_MINUTES,
  MAX_QUANTITY,
  MAX_SERVICES,
  parseItems,
  ServiceLineSel,
} from '../../shared/service-lines';

const TIPS = [0, 20, 50, 100];

/**
 * Book a Genie: services (one or several, Phase 3.5C) → day and time → address → payment and tip → confirm.
 * One page (not a multi-screen wizard) so people can see and change every choice; the summary follows them.
 */
@Component({
  selector: 'pg-checkout-page',
  imports: [
    RouterLink,
    CurrencyPipe,
    DatePipe,
    DecimalPipe,
    FormsModule,
    LucideDynamicIcon,
    AddressForm,
    CouponPicker,
    Avatar,
    Dialog,
    EmptyState,
    Rating,
    Skeleton,
    SlotPicker,
    Spinner,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="container-page pt-6 sm:pt-8 lg:pb-20">
      <a [routerLink]="['/genies', genieId()]" class="inline-flex items-center gap-1 text-sm font-semibold text-muted hover:text-brand">
        <svg [lucideIcon]="ArrowLeft" [size]="16" aria-hidden="true"></svg> Back to profile
      </a>

      @if (genie.value(); as g) {
        <h1 class="mt-3 text-3xl font-bold sm:text-4xl">Book {{ g.genie.fullName.split(' ')[0] }}</h1>
        <p class="mt-1 text-muted">Pay after the job. Free cancellation until 2 hours before your slot.</p>

        <div class="mt-8 grid grid-cols-[minmax(0,1fr)] gap-6 lg:grid-cols-[minmax(0,1fr)_24rem] lg:gap-8">
          <div class="min-w-0 space-y-6">
            <!-- 1. Services -->
            <section class="card p-5 sm:p-6" aria-labelledby="s1">
              <h2 id="s1" class="flex items-center gap-3 text-lg font-bold"><span class="step">1</span> Services</h2>
              <p class="mt-1 text-sm text-muted">
                Add another of {{ g.genie.fullName.split(' ')[0] }}'s services to the same visit: one travel fee. Up to {{ maxServices }}, each up to {{ maxQuantity }} times.
              </p>
              <div class="mt-4 grid gap-3 sm:grid-cols-2">
                @for (s of g.services; track s.serviceId) {
                  @let qty = quantityOf(s.serviceId);
                  <div
                    class="flex flex-col rounded-2xl border transition"
                    [class]="qty ? 'border-brand bg-brand-mist/40 ring-2 ring-brand' : 'border-line bg-white hover:border-brand-soft'"
                    data-testid="checkout-service"
                  >
                    <button
                      type="button"
                      class="flex items-start justify-between gap-3 p-4 text-left disabled:opacity-50"
                      [attr.aria-pressed]="qty > 0"
                      [disabled]="!qty && lines().length >= maxServices"
                      (click)="toggleService(s.serviceId)"
                    >
                      <span>
                        <span class="block font-semibold">{{ s.name }}</span>
                        <span class="mt-1 inline-flex items-center gap-1 text-xs text-muted">
                          <svg [lucideIcon]="Clock" [size]="13" aria-hidden="true"></svg> ~{{ s.durationMinutes }} min
                        </span>
                        @if (s.inspectionFirst) {
                          <span class="chip ml-1 bg-brand-mist py-0.5 text-xs font-semibold text-brand-600">Inspection first</span>
                        }
                      </span>
                      <span class="font-display text-lg font-bold">{{ s.price | currency: 'INR' : 'symbol' : '1.0-0' }}</span>
                    </button>
                    @if (qty) {
                      <div class="flex items-center justify-between gap-3 border-t border-brand-soft/60 px-4 py-2">
                        <span class="text-sm text-muted" [id]="'qty-' + s.serviceId">How many</span>
                        <div class="flex items-center gap-2" role="group" [attr.aria-labelledby]="'qty-' + s.serviceId">
                          <button type="button" class="qty-btn" [attr.aria-label]="'One fewer ' + s.name" (click)="changeQuantity(s.serviceId, -1)">
                            <svg [lucideIcon]="Minus" [size]="16" aria-hidden="true"></svg>
                          </button>
                          <span class="w-6 text-center font-semibold tabular-nums" aria-live="polite" [attr.data-testid]="'qty-value-' + s.serviceId">{{ qty }}</span>
                          <button type="button" class="qty-btn" [attr.aria-label]="'One more ' + s.name" [disabled]="qty >= maxQuantity" (click)="changeQuantity(s.serviceId, 1)">
                            <svg [lucideIcon]="Plus" [size]="16" aria-hidden="true"></svg>
                          </button>
                        </div>
                      </div>
                    }
                  </div>
                }
              </div>
              @if (lines().length) {
                <p class="mt-3 text-sm" [class]="tooLong() ? 'text-rose-700' : 'text-muted'" data-testid="checkout-duration" [attr.role]="tooLong() ? 'alert' : null">
                  @if (inspection()) {
                    <span data-testid="checkout-inspection">An inspection visit first (30 min). {{ g.genie.fullName.split(' ')[0] }} then sends you a priced quote; you book the job from it.</span>
                  } @else if (tooLong()) {
                    That's {{ durationLabel() }} of work, more than one day (8 h). Remove a service or book the rest separately.
                  } @else {
                    About {{ durationLabel() }} in total.
                  }
                </p>
              }
            </section>

            <!-- 2. Time -->
            <section class="card p-5 sm:p-6" aria-labelledby="s2">
              <h2 id="s2" class="flex items-center gap-3 text-lg font-bold"><span class="step">2</span> Day and time</h2>
              @if (items() && !tooLong()) {
                <pg-slot-picker #picker class="mt-4" [genieId]="genieId()" [items]="items()" [selected]="slot()?.start ?? null" (picked)="slot.set($event)" />
              } @else {
                <p class="mt-3 text-sm text-muted">{{ tooLong() ? 'Shorten the job first.' : 'Choose a service first.' }}</p>
              }
            </section>

            <!-- 3. Address -->
            <section class="card p-5 sm:p-6" aria-labelledby="s3">
              <div class="flex items-center justify-between gap-3">
                <h2 id="s3" class="flex items-center gap-3 text-lg font-bold"><span class="step">3</span> Address</h2>
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
                      <pg-empty [icon]="MapPin" title="Add where the job is" message="Pin your home on the map so the Genie finds you and the travel fee is exact.">
                        <button type="button" class="btn-primary" (click)="addressOpen.set(true)">Add address</button>
                      </pg-empty>
                    </div>
                  }
                }
              </div>
              @if (areaError()) {
                <p class="mt-3 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ areaError() }}</p>
              }
            </section>

            <!-- 4. Payment, tip, note -->
            <section class="card p-5 sm:p-6" aria-labelledby="s4">
              <h2 id="s4" class="flex items-center gap-3 text-lg font-bold"><span class="step">4</span> Payment and notes</h2>
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

              <label class="label mt-6" for="notes">Note for the Genie <span class="font-normal text-muted">(optional)</span></label>
              <textarea
                id="notes"
                class="field min-h-24"
                maxlength="500"
                placeholder="e.g. Two ceiling fans, ladder available. Gate code 1234."
                [ngModel]="notes()"
                (ngModelChange)="notes.set($event)"
              ></textarea>
            </section>

            <!-- 5. Coupon -->
            <section class="card p-5 sm:p-6" aria-labelledby="s5" [hidden]="inspection()">
              <h2 id="s5" class="flex items-center gap-3 text-lg font-bold"><span class="step">5</span> Coupon <span class="text-sm font-normal text-muted">(optional)</span></h2>
              <p class="mt-1 text-sm text-muted">Coupons take money off the service price. Your Genie still earns the full amount.</p>
              <pg-coupon-picker #coupons class="mt-4 block" [genieId]="genieId()" [items]="items() || null" [addressId]="addressId()" [(applied)]="coupon" />
            </section>
          </div>

          <!-- Summary -->
          <aside class="lg:sticky lg:top-[calc(var(--header-h)+1.5rem)] lg:self-start" id="summary">
            <div class="card overflow-hidden">
              <div class="flex items-center gap-3 bg-navy p-5 text-white">
                <pg-avatar [name]="g.genie.fullName" [size]="52" [online]="g.genie.online" />
                <div class="min-w-0">
                  <p class="truncate font-semibold">{{ g.genie.fullName }}</p>
                  <p class="truncate text-sm text-brand-mist">{{ g.genie.categories }}</p>
                  <pg-rating class="[&_.text-ink]:text-white [&_.text-muted]:text-brand-soft" [value]="g.genie.avgRating" [count]="g.genie.ratingCount" />
                </div>
              </div>
              <div class="space-y-3 p-5 text-sm">
                <div class="flex justify-between gap-3">
                  <span class="text-muted">{{ chosen().length > 1 ? 'Services' : 'Service' }}</span>
                  <span class="text-right font-medium" data-testid="summary-services">
                    @for (c of chosen(); track c.serviceId) {
                      <span class="block">{{ c.name }}{{ c.quantity > 1 ? ' ×' + c.quantity : '' }}</span>
                    } @empty {
                      —
                    }
                  </span>
                </div>
                <div class="flex justify-between gap-3">
                  <span class="text-muted">When</span>
                  <span class="text-right font-medium">{{ slot() ? (slot()!.start | date: 'EEE d MMM, h:mm a') : '—' }}</span>
                </div>
                <div class="flex justify-between gap-3">
                  <span class="text-muted">Where</span><span class="truncate text-right font-medium">{{ address()?.label ?? '—' }}{{ address()?.area ? ' · ' + address()!.area : '' }}</span>
                </div>

                <div class="border-t border-line pt-3">
                  @if (quote(); as q) {
                    <dl class="space-y-2">
                      @if (q.inspection) {
                        <div class="flex justify-between"><dt class="text-muted">Inspection visit</dt><dd>{{ q.inspectionFee | currency: 'INR' }}</dd></div>
                      } @else if (q.lines.length > 1) {
                        @for (l of q.lines; track l.serviceId) {
                          <div class="flex justify-between"><dt class="text-muted">{{ l.serviceName }}{{ l.quantity > 1 ? ' ×' + l.quantity : '' }}</dt><dd>{{ l.amount | currency: 'INR' }}</dd></div>
                        }
                      } @else {
                        <div class="flex justify-between"><dt class="text-muted">Service price{{ q.lines[0] && q.lines[0].quantity > 1 ? ' (×' + q.lines[0].quantity + ')' : '' }}</dt><dd>{{ q.serviceAmount | currency: 'INR' }}</dd></div>
                      }
                      <div class="flex justify-between">
                        <dt class="text-muted">Travel ({{ q.distanceMethod === 'ROAD' ? '' : '≈ ' }}{{ q.distanceKm | number: '1.1-1' }} km{{ q.distanceMethod === 'ROAD' ? ' by road' : '' }})</dt>
                        <dd>{{ q.travelFee > 0 ? (q.travelFee | currency: 'INR') : 'Free' }}</dd>
                      </div>
                      @if (q.tipAmount > 0) {
                        <div class="flex justify-between"><dt class="text-muted">Tip</dt><dd>{{ q.tipAmount | currency: 'INR' }}</dd></div>
                      }
                      @if (coupon(); as c) {
                        <div class="flex justify-between text-emerald-700"><dt>Coupon {{ c.code }}</dt><dd>− {{ c.discount | currency: 'INR' }}</dd></div>
                      }
                      <div class="flex justify-between border-t border-line pt-2 text-base font-bold">
                        <dt>Total</dt><dd>{{ total() | currency: 'INR' }}</dd>
                      </div>
                    </dl>
                    <p class="mt-2 text-xs text-muted">
                      {{ q.inspection ? 'The inspection fee is taken off the job if you accept the quote.' : 'Parts or materials, if needed, are added by the Genie at the end with a note.' }}
                    </p>
                  } @else if (chosen().length) {
                    <p class="flex justify-between font-semibold">
                      <span>From</span><span>{{ fromPrice() | currency: 'INR' }}</span>
                    </p>
                    <p class="mt-1 text-xs text-muted">Choose an address to see the travel fee.</p>
                  }
                </div>

                @if (error()) {
                  <p class="rounded-xl bg-rose-50 px-3 py-2 text-rose-800" role="alert">
                    {{ error() }}
                    @if (feeDue()) {
                      <a routerLink="/account/bookings" [queryParams]="{}" class="mt-1 block font-semibold underline">Go to my bookings</a>
                    }
                  </p>
                }

                <button type="button" class="btn-gold hidden min-h-12 w-full lg:inline-flex" [disabled]="!ready() || submitting()" (click)="submit()">
                  @if (submitting()) {
                    <pg-spinner />
                  }
                  {{ ready() ? (inspection() ? 'Book the inspection' : 'Confirm booking') : missingText() }}
                </button>
                <p class="flex items-start gap-2 text-xs text-muted">
                  <svg [lucideIcon]="ShieldCheck" [size]="14" class="mt-0.5 shrink-0 text-brand" aria-hidden="true"></svg>
                  Nothing is charged now. {{ g.genie.fullName.split(' ')[0] }} has 30 minutes to accept, or you can book someone else.
                </p>
              </div>
            </div>
          </aside>
        </div>

        <!-- Phone / tablet action bar -->
        <div class="sticky bottom-0 z-30 -mx-4 mt-8 border-t border-line bg-white/95 px-4 py-3 sm:-mx-6 sm:px-6 shadow-[0_-8px_24px_-12px_rgb(19_26_46/0.25)] backdrop-blur lg:hidden">
          <div class="mx-auto flex max-w-3xl items-center gap-3">
            <div class="min-w-0 flex-1">
              <p class="text-xs text-muted">{{ quote() ? 'Total' : 'From' }}</p>
              <p class="font-display text-xl font-bold">{{ (total() ?? fromPrice()) | currency: 'INR' : 'symbol' : '1.2-2' }}</p>
            </div>
            <button type="button" class="btn-gold min-h-12 px-6" [disabled]="!ready() || submitting()" (click)="submit()">
              @if (submitting()) {
                <pg-spinner />
              } @else if (ready()) {
                <svg [lucideIcon]="CircleCheck" [size]="18" aria-hidden="true"></svg>
              }
              {{ ready() ? (inspection() ? 'Book inspection' : 'Confirm') : missingText() }}
            </button>
          </div>
        </div>

        <pg-dialog [open]="addressOpen()" heading="New address" size="lg" (closed)="addressOpen.set(false)">
          @if (addressOpen()) {
            <pg-address-form (saved)="onAddressSaved($event)" (cancelled)="addressOpen.set(false)" />
          }
        </pg-dialog>
      } @else if (genie.isLoading()) {
        <pg-skeleton class="mt-6 block" [rows]="4" />
      } @else if (genie.error()) {
        <pg-empty class="mt-6 block" title="This Genie is not available" message="They may no longer be taking bookings.">
          <a routerLink="/services" class="btn-primary">Browse services</a>
        </pg-empty>
      }
    </div>
  `,
  styles: `
    .qty-btn {
      display: inline-grid;
      place-items: center;
      width: 2.25rem;
      height: 2.25rem;
      border-radius: 9999px;
      border: 1px solid var(--color-line);
      background: white;
    }
    .qty-btn:disabled {
      opacity: 0.4;
    }
    .step {
      display: inline-grid;
      place-items: center;
      width: 1.75rem;
      height: 1.75rem;
      border-radius: 9999px;
      background: var(--color-navy);
      color: white;
      font-size: 0.8rem;
    }
  `,
})
export default class CheckoutPage {
  readonly genieId = input.required<string>();
  /** Optional ?serviceId= (one service) or ?items=12:2,15:1 (several, Phase 3.5C) from the Genie profile or "Book again". */
  readonly serviceId = input<string | undefined>(undefined);
  readonly itemsFromUrl = input<string | undefined>(undefined, { alias: 'items' });
  /** Optional ?addressId= from "Book again" (Phase 3.5A): that saved address is preselected. */
  readonly addressFromUrl = input<string | undefined>(undefined, { alias: 'addressId' });

  private readonly api = inject(ApiClient);
  private readonly customer = inject(CustomerApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  protected readonly genie = httpResource<GenieDetail>(() => `${API}/genies/${encodeURIComponent(this.genieId())}`);
  protected readonly addresses = httpResource<Address[]>(() => `${API}/me/addresses`);

  /** The chosen services and quantities, in the order they were picked (Phase 3.5C). */
  protected readonly lines = signal<ServiceLineSel[]>([]);
  private linesSet = false;
  protected readonly maxServices = MAX_SERVICES;
  protected readonly maxQuantity = MAX_QUANTITY;
  protected readonly slot = signal<Slot | null>(null);
  protected readonly addressId = signal<string | null>(null);
  protected readonly method = signal<PaymentMethod>('CASH');
  protected readonly tip = signal(0);
  protected readonly notes = signal('');
  protected readonly coupon = signal<CouponOffer | null>(null);
  private readonly couponPicker = viewChild<CouponPicker>('coupons');
  protected readonly addressOpen = signal(false);
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly areaError = signal<string | null>(null);
  protected readonly feeDue = signal(false);
  private idempotencyKey = newIdempotencyKey();
  private readonly picker = viewChild<SlotPicker>('picker');

  protected readonly tips = TIPS;
  protected readonly methods = [
    { value: 'CASH' as const, label: 'Cash after the job', hint: 'Pay the Genie when the work is done.', icon: Banknote },
    { value: 'ONLINE' as const, label: 'Pay online after the job', hint: 'UPI or card in the app once it is done.', icon: CreditCard },
  ];

  protected readonly items = computed(() => itemsParam(this.lines()));
  /** The chosen lines with the Genie's names, prices and durations. */
  protected readonly chosen = computed(() => {
    const services = this.genie.value()?.services ?? [];
    return this.lines()
      .map((l) => {
        const s = services.find((x) => x.serviceId === l.serviceId);
        return s ? { ...l, name: s.name, price: s.price, minutes: s.durationMinutes, inspectionFirst: s.inspectionFirst } : null;
      })
      .filter((c): c is NonNullable<typeof c> => c !== null);
  });
  protected readonly minutes = computed(() => this.chosen().reduce((sum, c) => sum + c.minutes * c.quantity, 0));
  /** Phase 3.5D: one of the services needs an inspection visit first (30 min, a fee, then a quote). */
  protected readonly inspection = computed(() => this.chosen().some((c) => c.inspectionFirst));
  protected readonly tooLong = computed(() => !this.inspection() && this.minutes() > MAX_MINUTES);
  protected readonly durationLabel = computed(() => durationText(this.minutes()));
  protected readonly fromPrice = computed(() =>
    this.inspection() ? (this.quote()?.inspectionFee ?? 0) : this.chosen().reduce((sum, c) => sum + c.price * c.quantity, 0),
  );
  protected readonly address = computed(() => this.addresses.value()?.find((a) => a.id === this.addressId()) ?? null);

  /** Live quote whenever service, address or tip change. */
  private readonly quoteInput = computed(() => {
    const items = this.items();
    const a = this.address();
    return items && a && !this.tooLong() ? { items, lat: a.lat, lng: a.lng, tip: this.tip() } : null;
  }, { equal: (x, y) => x?.items === y?.items && x?.lat === y?.lat && x?.lng === y?.lng && x?.tip === y?.tip });
  protected readonly quote = toSignal(
    toObservable(this.quoteInput).pipe(
      debounceTime(150),
      switchMap((q) => {
        this.areaError.set(null);
        if (!q) return of(null);
        return this.api.estimate(this.genieId(), q.items, q.lat, q.lng, q.tip).pipe(
          catchError((err) => {
            this.areaError.set(errorMessage(err, 'Could not price this address.'));
            return of(null as PriceBreakdown | null);
          }),
        );
      }),
    ),
    { initialValue: null },
  );

  /** What the customer pays: the quote minus the coupon (on the service price only, already capped by the API). */
  protected readonly total = computed(() => {
    const q = this.quote();
    return q ? Math.max(0, Math.round((q.totalAmount - (this.coupon()?.discount ?? 0)) * 100) / 100) : null;
  });

  protected readonly ready = computed(
    () => this.chosen().length > 0 && !this.tooLong() && !!this.slot() && !!this.address() && !this.areaError(),
  );
  protected readonly missingText = computed(() =>
    !this.chosen().length ? 'Choose a service' : this.tooLong() ? 'Shorten the job' : !this.slot() ? 'Pick a time' : !this.address() ? 'Add an address' : 'Check the address',
  );

  constructor() {
    // Preselect the services from the URL (those this Genie still offers), else the Genie's first service.
    effect(() => {
      const g = this.genie.value();
      if (!g) return;
      untracked(() => {
        if (this.linesSet) return;
        this.linesSet = true;
        const offered = (l: ServiceLineSel) => g.services.some((s) => s.serviceId === l.serviceId);
        const fromUrl = parseItems(this.itemsFromUrl() ?? this.serviceId()).filter(offered);
        const first = g.services[0];
        this.lines.set(fromUrl.length ? fromUrl : first ? [{ serviceId: first.serviceId, quantity: 1 }] : []);
      });
    });
    // A different job length means different free times: pick the time again.
    let lastMinutes = 0;
    effect(() => {
      const m = this.minutes();
      untracked(() => {
        if (lastMinutes && m !== lastMinutes) this.slot.set(null);
        lastMinutes = m;
      });
    });
    // Preselect the default address.
    effect(() => {
      const list = this.addresses.value();
      if (!list?.length) return;
      untracked(() => {
        if (this.addressId()) return;
        const fromUrl = list.find((a) => a.id === this.addressFromUrl());
        this.addressId.set((fromUrl ?? list.find((a) => a.isDefault) ?? list[0]).id);
      });
    });
  }

  protected quantityOf(id: number): number {
    return this.lines().find((l) => l.serviceId === id)?.quantity ?? 0;
  }

  protected toggleService(id: number): void {
    const list = this.lines();
    if (list.some((l) => l.serviceId === id)) {
      this.lines.set(list.filter((l) => l.serviceId !== id));
    } else if (list.length < MAX_SERVICES) {
      this.lines.set([...list, { serviceId: id, quantity: 1 }]);
    }
  }

  protected changeQuantity(id: number, delta: number): void {
    this.lines.set(
      this.lines()
        .map((l) => (l.serviceId === id ? { ...l, quantity: Math.min(MAX_QUANTITY, l.quantity + delta) } : l))
        .filter((l) => l.quantity > 0),
    );
  }

  protected onAddressSaved(a: Address): void {
    this.addressOpen.set(false);
    this.addresses.reload();
    this.addressId.set(a.id);
  }

  protected submit(): void {
    const lines = this.lines();
    const slot = this.slot();
    const a = this.address();
    if (!lines.length || !slot || !a || this.submitting()) return;
    this.submitting.set(true);
    this.error.set(null);
    this.feeDue.set(false);
    this.customer
      .createBooking(
        {
          genieId: this.genieId(),
          items: lines,
          addressId: a.id,
          slotStart: slot.start,
          paymentMethod: this.method(),
          tipAmount: this.tip(),
          notes: this.notes().trim() || null,
          couponCode: this.coupon()?.code ?? null,
        },
        this.idempotencyKey,
      )
      .subscribe({
        next: (b) => {
          this.toast.success('Booking requested! We will let you know when the Genie accepts.');
          this.router.navigate(['/account/bookings', b.id]);
        },
        error: (err) => {
          this.submitting.set(false);
          const code = errorCode(err);
          this.error.set(errorMessage(err, 'Could not create the booking.'));
          if (code === 'SLOT_TAKEN' || code === 'SLOT_UNAVAILABLE' || code === 'SLOT_TOO_SOON' || code === 'SERVICE_NOT_OFFERED') {
            this.slot.set(null);
            this.picker()?.reload();
            document.getElementById('s2')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
          }
          if (code === 'OUTSTANDING_FEE') this.feeDue.set(true);
          if (code?.startsWith('COUPON_')) {
            this.couponPicker()?.reject(errorMessage(err));
            this.error.set('Your coupon could not be used. Remove it or try another one, then confirm again.');
          }
          // A rejected request created nothing, but use a fresh key so a changed retry is never deduplicated.
          this.idempotencyKey = newIdempotencyKey();
        },
      });
  }

  protected readonly ArrowLeft = ArrowLeft;
  protected readonly Clock = Clock;
  protected readonly MapPin = MapPin;
  protected readonly Plus = Plus;
  protected readonly Minus = Minus;
  protected readonly ShieldCheck = ShieldCheck;
  protected readonly CircleCheck = CircleCheck;
}
