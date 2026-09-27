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
  LucidePlus as Plus,
  LucideShieldCheck as ShieldCheck,
} from '@lucide/angular';
import { debounceTime, of, switchMap, catchError } from 'rxjs';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';

import { API, ApiClient, errorCode, errorMessage } from '../../core/api/api';
import { CustomerApi } from '../../core/api/customer-api';
import { Address, GenieDetail, PaymentMethod, PriceBreakdown, Slot } from '../../core/api/models';
import { ToastService } from '../../core/ui/feedback';
import { newIdempotencyKey } from '../../shared/format';
import { Avatar } from '../../shared/ui/avatar';
import { Dialog } from '../../shared/ui/dialog';
import { EmptyState, Skeleton, Spinner } from '../../shared/ui/kit';
import { Rating } from '../../shared/ui/rating';
import { SlotPicker } from '../../shared/ui/slot-picker';
import { AddressForm } from './address-form';

const TIPS = [0, 20, 50, 100];

/**
 * Book a Genie: service → day and time → address → payment and tip → confirm.
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
            <!-- 1. Service -->
            <section class="card p-5 sm:p-6" aria-labelledby="s1">
              <h2 id="s1" class="flex items-center gap-3 text-lg font-bold"><span class="step">1</span> Service</h2>
              <div class="mt-4 grid gap-3 sm:grid-cols-2">
                @for (s of g.services; track s.serviceId) {
                  <button
                    type="button"
                    class="flex items-start justify-between gap-3 rounded-2xl border p-4 text-left transition"
                    [class]="s.serviceId === serviceIdNum() ? 'border-brand bg-brand-mist/40 ring-2 ring-brand' : 'border-line bg-white hover:border-brand-soft'"
                    [attr.aria-pressed]="s.serviceId === serviceIdNum()"
                    (click)="chooseService(s.serviceId)"
                  >
                    <span>
                      <span class="block font-semibold">{{ s.name }}</span>
                      <span class="mt-1 inline-flex items-center gap-1 text-xs text-muted">
                        <svg [lucideIcon]="Clock" [size]="13" aria-hidden="true"></svg> ~{{ s.durationMinutes }} min
                      </span>
                    </span>
                    <span class="font-display text-lg font-bold">{{ s.price | currency: 'INR' : 'symbol' : '1.0-0' }}</span>
                  </button>
                }
              </div>
            </section>

            <!-- 2. Time -->
            <section class="card p-5 sm:p-6" aria-labelledby="s2">
              <h2 id="s2" class="flex items-center gap-3 text-lg font-bold"><span class="step">2</span> Day and time</h2>
              @if (serviceIdNum(); as sid) {
                <pg-slot-picker #picker class="mt-4" [genieId]="genieId()" [serviceId]="sid" [selected]="slot()?.start ?? null" (picked)="slot.set($event)" />
              } @else {
                <p class="mt-3 text-sm text-muted">Choose a service first.</p>
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
                  <span class="text-muted">Service</span><span class="text-right font-medium">{{ service()?.name ?? '—' }}</span>
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
                      <div class="flex justify-between"><dt class="text-muted">Service price</dt><dd>{{ q.serviceAmount | currency: 'INR' }}</dd></div>
                      <div class="flex justify-between">
                        <dt class="text-muted">Travel ({{ q.distanceKm | number: '1.1-1' }} km)</dt>
                        <dd>{{ q.travelFee > 0 ? (q.travelFee | currency: 'INR') : 'Free' }}</dd>
                      </div>
                      @if (q.tipAmount > 0) {
                        <div class="flex justify-between"><dt class="text-muted">Tip</dt><dd>{{ q.tipAmount | currency: 'INR' }}</dd></div>
                      }
                      <div class="flex justify-between border-t border-line pt-2 text-base font-bold">
                        <dt>Total</dt><dd>{{ q.totalAmount | currency: 'INR' }}</dd>
                      </div>
                    </dl>
                    <p class="mt-2 text-xs text-muted">Parts or materials, if needed, are added by the Genie at the end with a note.</p>
                  } @else if (service()) {
                    <p class="flex justify-between font-semibold">
                      <span>From</span><span>{{ service()!.price | currency: 'INR' }}</span>
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
                  {{ ready() ? 'Confirm booking' : missingText() }}
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
              <p class="font-display text-xl font-bold">{{ (quote()?.totalAmount ?? service()?.price ?? 0) | currency: 'INR' : 'symbol' : '1.0-2' }}</p>
            </div>
            <button type="button" class="btn-gold min-h-12 px-6" [disabled]="!ready() || submitting()" (click)="submit()">
              @if (submitting()) {
                <pg-spinner />
              } @else if (ready()) {
                <svg [lucideIcon]="CircleCheck" [size]="18" aria-hidden="true"></svg>
              }
              {{ ready() ? 'Confirm' : missingText() }}
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
  /** Optional ?serviceId= from the Genie profile page. */
  readonly serviceId = input<string | undefined>(undefined);

  private readonly api = inject(ApiClient);
  private readonly customer = inject(CustomerApi);
  private readonly router = inject(Router);
  private readonly toast = inject(ToastService);

  protected readonly genie = httpResource<GenieDetail>(() => `${API}/genies/${encodeURIComponent(this.genieId())}`);
  protected readonly addresses = httpResource<Address[]>(() => `${API}/me/addresses`);

  protected readonly selectedService = signal<number | null>(null);
  protected readonly slot = signal<Slot | null>(null);
  protected readonly addressId = signal<string | null>(null);
  protected readonly method = signal<PaymentMethod>('CASH');
  protected readonly tip = signal(0);
  protected readonly notes = signal('');
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

  protected readonly serviceIdNum = computed(() => this.selectedService());
  protected readonly service = computed(() => this.genie.value()?.services.find((s) => s.serviceId === this.selectedService()) ?? null);
  protected readonly address = computed(() => this.addresses.value()?.find((a) => a.id === this.addressId()) ?? null);

  /** Live quote whenever service, address or tip change. */
  private readonly quoteInput = computed(() => {
    const s = this.selectedService();
    const a = this.address();
    return s && a ? { serviceId: s, lat: a.lat, lng: a.lng, tip: this.tip() } : null;
  });
  protected readonly quote = toSignal(
    toObservable(this.quoteInput).pipe(
      debounceTime(150),
      switchMap((q) => {
        this.areaError.set(null);
        if (!q) return of(null);
        return this.api.estimate(this.genieId(), q.serviceId, q.lat, q.lng, q.tip).pipe(
          catchError((err) => {
            this.areaError.set(errorMessage(err, 'Could not price this address.'));
            return of(null as PriceBreakdown | null);
          }),
        );
      }),
    ),
    { initialValue: null },
  );

  protected readonly ready = computed(() => !!this.service() && !!this.slot() && !!this.address() && !this.areaError());
  protected readonly missingText = computed(() =>
    !this.service() ? 'Choose a service' : !this.slot() ? 'Pick a time' : !this.address() ? 'Add an address' : 'Check the address',
  );

  constructor() {
    // Preselect the service from the URL, else the Genie's first service.
    effect(() => {
      const g = this.genie.value();
      if (!g) return;
      untracked(() => {
        if (this.selectedService() !== null) return;
        const fromUrl = Number(this.serviceId());
        const match = g.services.find((s) => s.serviceId === fromUrl) ?? g.services[0];
        this.selectedService.set(match?.serviceId ?? null);
      });
    });
    // Preselect the default address.
    effect(() => {
      const list = this.addresses.value();
      if (!list?.length) return;
      untracked(() => {
        if (!this.addressId()) this.addressId.set((list.find((a) => a.isDefault) ?? list[0]).id);
      });
    });
  }

  protected chooseService(id: number): void {
    if (id === this.selectedService()) return;
    this.selectedService.set(id);
    this.slot.set(null); // durations differ, so free times differ too
  }

  protected onAddressSaved(a: Address): void {
    this.addressOpen.set(false);
    this.addresses.reload();
    this.addressId.set(a.id);
  }

  protected submit(): void {
    const s = this.service();
    const slot = this.slot();
    const a = this.address();
    if (!s || !slot || !a || this.submitting()) return;
    this.submitting.set(true);
    this.error.set(null);
    this.feeDue.set(false);
    this.customer
      .createBooking(
        {
          genieId: this.genieId(),
          serviceId: s.serviceId,
          addressId: a.id,
          slotStart: slot.start,
          paymentMethod: this.method(),
          tipAmount: this.tip(),
          notes: this.notes().trim() || null,
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
          if (code === 'SLOT_TAKEN' || code === 'SLOT_UNAVAILABLE' || code === 'SLOT_TOO_SOON') {
            this.slot.set(null);
            this.picker()?.reload();
            document.getElementById('s2')?.scrollIntoView({ behavior: 'smooth', block: 'start' });
          }
          if (code === 'OUTSTANDING_FEE') this.feeDue.set(true);
          // A rejected request created nothing, but use a fresh key so a changed retry is never deduplicated.
          this.idempotencyKey = newIdempotencyKey();
        },
      });
  }

  protected readonly ArrowLeft = ArrowLeft;
  protected readonly Clock = Clock;
  protected readonly MapPin = MapPin;
  protected readonly Plus = Plus;
  protected readonly ShieldCheck = ShieldCheck;
  protected readonly CircleCheck = CircleCheck;
}
