import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { CurrencyPipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { LucideDynamicIcon, LucidePencil as Pencil, LucidePlus as Plus, LucideTrash2 as Trash } from '@lucide/angular';

import { API, errorMessage } from '../../core/api/api';
import { AdminApi } from '../../core/api/admin-api';
import { AdminCity, AdminPincode } from '../../core/api/models';
import { CityStore } from '../../core/city/city-store';
import { ConfirmService, ToastService } from '../../core/ui/feedback';
import { Dialog } from '../../shared/ui/dialog';
import { Spinner } from '../../shared/ui/kit';
import { LatLng, MapPicker } from '../../shared/ui/map-picker';
import { StatusBadge } from '../../shared/ui/status-badge';

function slugify(text: string): string {
  return text
    .toLowerCase()
    .trim()
    .replace(/&/g, 'and')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
}

/**
 * Cities, their PIN codes and pricing. Opening a city is data only:
 * 1. New city (starts switched off, with default pricing) · 2. add its PIN codes · 3. check pricing · 4. switch it on.
 * It then appears in the customer city switcher and its PIN codes are accepted for addresses at once.
 */
@Component({
  selector: 'pg-admin-cities',
  imports: [CurrencyPipe, DecimalPipe, FormsModule, LucideDynamicIcon, Dialog, MapPicker, Spinner, StatusBadge],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mt-5 flex flex-wrap items-center justify-between gap-3">
      <p class="max-w-2xl text-sm text-muted">
        To open a city: create it (it starts switched off), add the PIN codes you serve, check its pricing, then switch it on. No release is
        needed.
      </p>
      <button type="button" class="btn-primary" (click)="editCity(null)"><svg [lucideIcon]="Plus" [size]="18" aria-hidden="true"></svg> New city</button>
    </div>

    <div class="mt-4 space-y-4">
      @for (c of cities.value() ?? []; track c.cityId) {
        <section class="card p-5" [attr.aria-label]="c.name">
          <div class="flex flex-wrap items-start justify-between gap-3">
            <div>
              <h2 class="text-lg font-bold">{{ c.name }}, {{ c.state }}</h2>
              <p class="mt-0.5 text-xs text-muted">
                /{{ c.slug }} · {{ c.pincodeCount }} active PIN code{{ c.pincodeCount === 1 ? '' : 's' }} · within {{ c.radiusKm }} km of
                {{ c.centerLat | number: '1.3-3' }}, {{ c.centerLng | number: '1.3-3' }}
              </p>
            </div>
            <div class="flex flex-wrap items-center gap-2">
              <pg-status [status]="c.active ? 'ACTIVE' : 'INACTIVE'" [label]="c.active ? 'Live' : 'Switched off'" [tone]="c.active ? 'success' : 'neutral'" />
              <button type="button" class="grid size-9 place-items-center rounded-full text-muted hover:bg-surface hover:text-brand" [attr.aria-label]="'Edit ' + c.name" (click)="editCity(c)">
                <svg [lucideIcon]="Pencil" [size]="16" aria-hidden="true"></svg>
              </button>
              <button type="button" class="btn-ghost min-h-9 px-3 text-sm" [disabled]="busy() === c.cityId" (click)="toggleLive(c)">
                {{ c.active ? 'Switch off' : 'Switch on' }}
              </button>
            </div>
          </div>
          @if (!c.active && c.pincodeCount === 0) {
            <p class="mt-3 rounded-xl bg-amber-50 px-4 py-2 text-sm text-amber-900">Add at least one PIN code before switching {{ c.name }} on.</p>
          }

          <!-- PIN codes -->
          <details class="mt-4 rounded-xl border border-line" [open]="openPins() === c.cityId" (toggle)="onPinsToggle(c, $event)">
            <summary class="cursor-pointer px-4 py-3 text-sm font-semibold">PIN codes</summary>
            @if (openPins() === c.cityId) {
              <div class="border-t border-line p-4">
                <form class="grid gap-3 sm:grid-cols-[8rem_1fr_7rem_7rem_auto] sm:items-end" (ngSubmit)="addPincode(c)">
                  <div>
                    <label class="label" [for]="'np-' + c.cityId">PIN code</label>
                    <input [id]="'np-' + c.cityId" name="pincode" class="field" inputmode="numeric" maxlength="6" placeholder="390007" [(ngModel)]="pinForm.pincode" />
                  </div>
                  <div>
                    <label class="label" [for]="'na-' + c.cityId">Area name</label>
                    <input [id]="'na-' + c.cityId" name="area" class="field" maxlength="120" placeholder="Alkapuri" [(ngModel)]="pinForm.area" />
                  </div>
                  <div>
                    <label class="label" [for]="'nl-' + c.cityId">Lat (optional)</label>
                    <input [id]="'nl-' + c.cityId" name="lat" type="number" step="0.0001" class="field" [(ngModel)]="pinForm.lat" />
                  </div>
                  <div>
                    <label class="label" [for]="'ng-' + c.cityId">Lng (optional)</label>
                    <input [id]="'ng-' + c.cityId" name="lng" type="number" step="0.0001" class="field" [(ngModel)]="pinForm.lng" />
                  </div>
                  <button type="submit" class="btn-primary" [disabled]="busy() === c.cityId || !validPin(pinForm.pincode)">Add</button>
                </form>
                <p class="mt-2 text-xs text-muted">The centre point is optional; it is used for price estimates on Genie profiles.</p>

                @if (pinsLoading()) {
                  <div class="py-6 text-center"><pg-spinner /></div>
                } @else {
                  <ul class="mt-4 divide-y divide-line">
                    @for (p of pins(); track p.pincode) {
                      <li class="flex flex-wrap items-center gap-3 py-2 text-sm" [class.opacity-60]="!p.active">
                        <span class="w-16 font-semibold tabular-nums">{{ p.pincode }}</span>
                        <span class="min-w-0 flex-1">{{ p.area || '—' }}</span>
                        <span class="text-xs text-muted tabular-nums">{{ p.lat !== null ? (p.lat | number: '1.3-3') + ', ' + (p.lng | number: '1.3-3') : 'no centre' }}</span>
                        <button type="button" class="btn-ghost min-h-9 px-3 text-xs" [disabled]="busy() === c.cityId" (click)="togglePincode(c, p)">
                          {{ p.active ? 'Pause' : 'Resume' }}
                        </button>
                        <button type="button" class="grid size-9 place-items-center rounded-full text-muted hover:bg-rose-50 hover:text-rose-700" [attr.aria-label]="'Remove ' + p.pincode" (click)="removePincode(c, p)">
                          <svg [lucideIcon]="Trash" [size]="16" aria-hidden="true"></svg>
                        </button>
                      </li>
                    } @empty {
                      <li class="py-4 text-sm text-muted">No PIN codes yet.</li>
                    }
                  </ul>
                }
              </div>
            }
          </details>

          <!-- Pricing -->
          <form class="mt-4 rounded-xl border border-line p-4" (ngSubmit)="savePricing(c)">
            <h3 class="text-sm font-semibold">Pricing</h3>
            <div class="mt-3 grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
              <div>
                <label class="label" [for]="'cr-' + c.cityId">Commission (%)</label>
                <input [id]="'cr-' + c.cityId" [name]="'cr-' + c.cityId" type="number" step="0.5" min="0" max="50" class="field" [ngModel]="percent(c)" (ngModelChange)="c.commissionRate = +$event / 100" />
              </div>
              <div>
                <label class="label" [for]="'fk-' + c.cityId">Free travel up to (km)</label>
                <input [id]="'fk-' + c.cityId" [name]="'fk-' + c.cityId" type="number" step="0.5" min="0" class="field" [(ngModel)]="c.travelFreeKm" />
              </div>
              <div>
                <label class="label" [for]="'bf-' + c.cityId">Base travel fee (₹)</label>
                <input [id]="'bf-' + c.cityId" [name]="'bf-' + c.cityId" type="number" min="0" class="field" [(ngModel)]="c.travelBaseFee" />
              </div>
              <div>
                <label class="label" [for]="'pk-' + c.cityId">Per extra km (₹)</label>
                <input [id]="'pk-' + c.cityId" [name]="'pk-' + c.cityId" type="number" min="0" class="field" [(ngModel)]="c.travelPerKm" />
              </div>
              <div>
                <label class="label" [for]="'cap-' + c.cityId">Travel fee cap (₹)</label>
                <input [id]="'cap-' + c.cityId" [name]="'cap-' + c.cityId" type="number" min="0" class="field" [(ngModel)]="c.travelFeeCap" />
              </div>
              <div>
                <label class="label" [for]="'if-' + c.cityId">Genie Now fee (₹)</label>
                <input [id]="'if-' + c.cityId" [name]="'if-' + c.cityId" type="number" min="0" max="1000" class="field" [(ngModel)]="c.instantFee" />
              </div>
            </div>
            <div class="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <p class="text-sm text-muted">
                Example, 6 km away: travel {{ example(c) | currency: 'INR' : 'symbol' : '1.0-0' }} · commission on a ₹500 job {{ c.commissionRate * 500 | currency: 'INR' : 'symbol' : '1.0-2' }}
              </p>
              <button type="submit" class="btn-primary" [disabled]="busy() === c.cityId">Save {{ c.name }}</button>
            </div>
          </form>
        </section>
      } @empty {
        @if (cities.isLoading()) {
          <div class="card h-40 animate-pulse"></div>
        }
      }
    </div>

    <pg-dialog [open]="cityOpen()" [heading]="cityForm.id ? 'Edit city' : 'New city'" size="lg" (closed)="cityOpen.set(false)">
      <form class="grid gap-4 sm:grid-cols-2" (ngSubmit)="saveCity()" #cf="ngForm">
        <div>
          <label class="label" for="ct-name">Name</label>
          <input id="ct-name" name="name" class="field" required maxlength="80" [(ngModel)]="cityForm.name" (ngModelChange)="cityForm.id ? null : (cityForm.slug = slugify($event))" placeholder="Vadodara" />
        </div>
        <div>
          <label class="label" for="ct-state">State</label>
          <input id="ct-state" name="state" class="field" required maxlength="80" [(ngModel)]="cityForm.state" placeholder="Gujarat" />
        </div>
        <div>
          <label class="label" for="ct-slug">Slug (in links and the city switcher)</label>
          <input id="ct-slug" name="slug" class="field" required maxlength="60" pattern="[a-z0-9]+(-[a-z0-9]+)*" [(ngModel)]="cityForm.slug" />
        </div>
        <div>
          <label class="label" for="ct-radius">Service radius: <span class="font-semibold text-brand">{{ cityForm.radiusKm }} km</span></label>
          <input id="ct-radius" name="radius" type="range" min="5" max="100" class="mt-3 w-full accent-brand" [(ngModel)]="cityForm.radiusKm" />
        </div>
        <div class="sm:col-span-2">
          <span class="label">City centre <span class="font-normal text-muted">(the map opens here; addresses must be within the radius)</span></span>
          @if (cityOpen()) {
            <pg-map-picker [lat]="cityForm.center?.lat ?? null" [lng]="cityForm.center?.lng ?? null" label="City centre" (moved)="cityForm.center = $event" />
          }
        </div>
        <div class="flex justify-end gap-2 sm:col-span-2">
          <button type="button" class="btn-ghost" (click)="cityOpen.set(false)">Cancel</button>
          <button type="submit" class="btn-primary" [disabled]="saving() || !cf.valid || !cityForm.center">
            @if (saving()) {
              <pg-spinner />
            }
            {{ cityForm.id ? 'Save city' : 'Create city (switched off)' }}
          </button>
        </div>
      </form>
    </pg-dialog>
  `,
})
export class AdminCities {
  private readonly api = inject(AdminApi);
  private readonly toast = inject(ToastService);
  private readonly confirm = inject(ConfirmService);
  private readonly liveCities = inject(CityStore);

  protected readonly cities = httpResource<AdminCity[]>(() => `${API}/admin/cities`);
  protected readonly busy = signal<number | null>(null);
  protected readonly saving = signal(false);
  protected readonly cityOpen = signal(false);
  protected readonly openPins = signal<number | null>(null);
  protected readonly pins = signal<AdminPincode[]>([]);
  protected readonly pinsLoading = signal(false);
  protected cityForm = this.blankCity();
  protected pinForm = this.blankPin();
  protected readonly slugify = slugify;

  protected readonly Pencil = Pencil;
  protected readonly Plus = Plus;
  protected readonly Trash = Trash;

  private blankCity() {
    return { id: null as number | null, name: '', state: '', slug: '', radiusKm: 30, center: null as LatLng | null };
  }

  private blankPin() {
    return { pincode: '', area: '', lat: null as number | null, lng: null as number | null };
  }

  protected validPin(p: string): boolean {
    return /^[1-9][0-9]{5}$/.test((p ?? '').trim());
  }

  protected editCity(c: AdminCity | null): void {
    this.cityForm = c
      ? {
          id: c.cityId,
          name: c.name,
          state: c.state,
          slug: c.slug,
          radiusKm: Number(c.radiusKm),
          center: c.centerLat !== null && c.centerLng !== null ? { lat: c.centerLat, lng: c.centerLng } : null,
        }
      : this.blankCity();
    this.cityOpen.set(true);
  }

  protected saveCity(): void {
    const f = this.cityForm;
    if (!f.center) return;
    this.saving.set(true);
    this.api
      .saveCity(f.id, { slug: f.slug.trim(), name: f.name.trim(), state: f.state.trim(), centerLat: f.center.lat, centerLng: f.center.lng, radiusKm: Number(f.radiusKm) })
      .subscribe({
        next: (c) => {
          this.saving.set(false);
          this.cityOpen.set(false);
          this.toast.success(f.id ? `${c.name} saved` : `${c.name} created. Add its PIN codes, then switch it on.`);
          this.cities.reload();
          this.liveCities.reload();
          if (!f.id) this.loadPins(c.cityId);
        },
        error: (err) => {
          this.saving.set(false);
          this.toast.error(errorMessage(err));
        },
      });
  }

  protected async toggleLive(c: AdminCity): Promise<void> {
    const on = !c.active;
    const { confirmed } = await this.confirm.ask(
      on
        ? { title: `Switch ${c.name} on?`, message: 'Customers can then pick it and save addresses with its PIN codes, and Genies based there can sign up.', confirmLabel: 'Switch on' }
        : {
            title: `Switch ${c.name} off?`,
            message: 'It disappears from the city switcher and its PIN codes stop being accepted for new addresses. Existing bookings are kept.',
            confirmLabel: 'Switch off',
            tone: 'danger',
          },
    );
    if (!confirmed) return;
    this.busy.set(c.cityId);
    this.api.setCityActive(c.cityId, on).subscribe({
      next: () => {
        this.busy.set(null);
        this.toast.success(on ? `${c.name} is live` : `${c.name} is switched off`);
        this.cities.reload();
        this.liveCities.reload();
      },
      error: (err) => {
        this.busy.set(null);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected onPinsToggle(c: AdminCity, event: Event): void {
    const open = (event.target as HTMLDetailsElement).open;
    if (open && this.openPins() !== c.cityId) this.loadPins(c.cityId);
    if (!open && this.openPins() === c.cityId) this.openPins.set(null);
  }

  private loadPins(cityId: number): void {
    this.openPins.set(cityId);
    this.pinForm = this.blankPin();
    this.pinsLoading.set(true);
    this.api.pincodes(cityId).subscribe({
      next: (list) => {
        this.pins.set(list);
        this.pinsLoading.set(false);
      },
      error: (err) => {
        this.pinsLoading.set(false);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected addPincode(c: AdminCity): void {
    const f = this.pinForm;
    const pincode = f.pincode.trim();
    if (!this.validPin(pincode)) return;
    const hasPoint = f.lat !== null && f.lng !== null && `${f.lat}` !== '' && `${f.lng}` !== '';
    this.savePin(c, pincode, { area: f.area.trim() || null, lat: hasPoint ? Number(f.lat) : null, lng: hasPoint ? Number(f.lng) : null, active: true }, `PIN code ${pincode} added`);
  }

  protected togglePincode(c: AdminCity, p: AdminPincode): void {
    this.savePin(c, p.pincode, { area: p.area, lat: p.lat, lng: p.lng, active: !p.active }, p.active ? `${p.pincode} paused` : `${p.pincode} resumed`);
  }

  private savePin(c: AdminCity, pincode: string, body: { area: string | null; lat: number | null; lng: number | null; active: boolean }, done: string): void {
    this.busy.set(c.cityId);
    this.api.savePincode(c.cityId, pincode, body).subscribe({
      next: () => {
        this.busy.set(null);
        this.toast.success(done);
        this.loadPins(c.cityId);
        this.cities.reload();
      },
      error: (err) => {
        this.busy.set(null);
        this.toast.error(errorMessage(err));
      },
    });
  }

  protected async removePincode(c: AdminCity, p: AdminPincode): Promise<void> {
    const { confirmed } = await this.confirm.ask({
      title: `Remove ${p.pincode}?`,
      message: 'New addresses with this PIN code will be refused. Saved addresses and bookings are kept.',
      confirmLabel: 'Remove',
      tone: 'danger',
    });
    if (!confirmed) return;
    this.busy.set(c.cityId);
    this.api.deletePincode(c.cityId, p.pincode).subscribe({
      next: () => {
        this.busy.set(null);
        this.toast.success(`${p.pincode} removed`);
        this.loadPins(c.cityId);
        this.cities.reload();
      },
      error: (err) => {
        this.busy.set(null);
        this.toast.error(errorMessage(err));
      },
    });
  }

  /** The API stores the rate as a fraction (0.05); admins edit a percentage (5). */
  protected percent(c: AdminCity): number {
    return Math.round(Number(c.commissionRate) * 10000) / 100;
  }

  protected example(c: AdminCity): number {
    const d = 6;
    if (d <= Number(c.travelFreeKm)) return 0;
    return Math.min(Number(c.travelBaseFee) + Number(c.travelPerKm) * (d - Number(c.travelFreeKm)), Number(c.travelFeeCap));
  }

  protected savePricing(c: AdminCity): void {
    this.busy.set(c.cityId);
    this.api
      .savePricing(c.cityId, {
        commissionRate: Number(c.commissionRate),
        travelFreeKm: Number(c.travelFreeKm),
        travelBaseFee: Number(c.travelBaseFee),
        travelPerKm: Number(c.travelPerKm),
        travelFeeCap: Number(c.travelFeeCap),
        instantFee: Number(c.instantFee),
      })
      .subscribe({
        next: () => {
          this.busy.set(null);
          this.toast.success(`${c.name} pricing saved. New quotes use it immediately.`);
        },
        error: (err) => {
          this.busy.set(null);
          this.toast.error(errorMessage(err));
          this.cities.reload();
        },
      });
  }
}
