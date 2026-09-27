import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { FormsModule } from '@angular/forms';

import { API, errorMessage } from '../../core/api/api';
import { CustomerApi } from '../../core/api/customer-api';
import { Address, AddressRequest, City } from '../../core/api/models';
import { ToastService } from '../../core/ui/feedback';
import { Spinner } from '../../shared/ui/kit';
import { LatLng, MapPicker } from '../../shared/ui/map-picker';

const LABELS = ['Home', 'Work', 'Other'];

/** Create / edit an address. The map pin is required: it drives the travel fee and the Genie's route. */
@Component({
  selector: 'pg-address-form',
  imports: [FormsModule, MapPicker, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <form class="space-y-4" (ngSubmit)="save()" #f="ngForm">
      <div>
        <span class="label">Save as</span>
        <div class="flex flex-wrap gap-2">
          @for (l of labels; track l) {
            <button
              type="button"
              class="min-h-10 rounded-full border px-4 text-sm font-semibold"
              [class]="model.label === l ? 'border-brand bg-brand text-white' : 'border-line bg-white hover:border-brand'"
              (click)="model.label = l"
            >
              {{ l }}
            </button>
          }
        </div>
      </div>

      <div>
        <span class="label">Pin the exact spot</span>
        <pg-map-picker [lat]="pin()?.lat ?? null" [lng]="pin()?.lng ?? null" (moved)="pin.set($event)" />
      </div>

      <div class="grid gap-4 sm:grid-cols-2">
        <div class="sm:col-span-2">
          <label class="label" for="a-line1">House / flat, building</label>
          <input id="a-line1" name="line1" class="field" required maxlength="200" [(ngModel)]="model.line1" placeholder="B-402, Shivalik Heights" />
        </div>
        <div class="sm:col-span-2">
          <label class="label" for="a-line2">Street, society (optional)</label>
          <input id="a-line2" name="line2" class="field" maxlength="200" [(ngModel)]="model.line2" placeholder="Near Shyamal Cross Road" />
        </div>
        <div>
          <label class="label" for="a-area">Area</label>
          <input id="a-area" name="area" class="field" maxlength="100" [(ngModel)]="model.area" placeholder="Satellite" />
        </div>
        <div>
          <label class="label" for="a-landmark">Landmark (optional)</label>
          <input id="a-landmark" name="landmark" class="field" maxlength="120" [(ngModel)]="model.landmark" placeholder="Opp. Iscon Mall" />
        </div>
        <div>
          <label class="label" for="a-city">City</label>
          <select id="a-city" name="cityId" class="field" [(ngModel)]="model.cityId">
            @for (c of cities.value() ?? []; track c.id) {
              <option [ngValue]="c.id">{{ c.name }}</option>
            }
          </select>
        </div>
        <div>
          <label class="label" for="a-pin">Pincode</label>
          <input
            id="a-pin"
            name="pincode"
            class="field"
            required
            inputmode="numeric"
            pattern="[0-9]{6}"
            maxlength="6"
            [(ngModel)]="model.pincode"
            placeholder="380015"
          />
        </div>
      </div>

      <label class="flex items-center gap-3 text-sm">
        <input type="checkbox" name="isDefault" class="size-5 rounded accent-brand" [(ngModel)]="model.isDefault" />
        Use as my default address
      </label>

      @if (error()) {
        <p class="rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ error() }}</p>
      }

      <div class="flex flex-col-reverse gap-2 pt-2 sm:flex-row sm:justify-end">
        <button type="button" class="btn-ghost" (click)="cancelled.emit()">Cancel</button>
        <button type="submit" class="btn-primary" [disabled]="saving() || !f.valid || !pin()">
          @if (saving()) {
            <pg-spinner />
          }
          {{ address() ? 'Save changes' : 'Save address' }}
        </button>
      </div>
      @if (!pin()) {
        <p class="text-right text-xs text-muted">Drop a pin on the map to continue.</p>
      }
    </form>
  `,
})
export class AddressForm {
  /** Address to edit; null to create a new one. */
  readonly address = input<Address | null>(null);
  readonly saved = output<Address>();
  readonly cancelled = output<void>();

  private readonly api = inject(CustomerApi);
  private readonly toast = inject(ToastService);
  protected readonly cities = httpResource<City[]>(() => `${API}/cities`);

  protected readonly labels = LABELS;
  protected readonly pin = signal<LatLng | null>(null);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected model = this.blank();

  private readonly defaultCity = computed(() => this.cities.value()?.find((c) => c.name === 'Ahmedabad')?.id ?? this.cities.value()?.[0]?.id ?? null);

  constructor() {
    effect(() => {
      const a = this.address();
      untracked(() => {
        this.model = a
          ? { label: a.label, line1: a.line1, line2: a.line2 ?? '', landmark: a.landmark ?? '', area: a.area ?? '', cityId: a.cityId, pincode: a.pincode, isDefault: a.isDefault }
          : this.blank();
        this.pin.set(a ? { lat: a.lat, lng: a.lng } : null);
        this.error.set(null);
      });
    });
    effect(() => {
      const city = this.defaultCity();
      untracked(() => {
        if (city !== null && this.model.cityId === null) this.model = { ...this.model, cityId: city };
      });
    });
  }

  private blank() {
    return { label: 'Home', line1: '', line2: '', landmark: '', area: '', cityId: null as number | null, pincode: '', isDefault: false };
  }

  protected save(): void {
    const pin = this.pin();
    if (!pin) return;
    const body: AddressRequest = {
      label: this.model.label,
      line1: this.model.line1.trim(),
      line2: this.model.line2.trim() || null,
      landmark: this.model.landmark.trim() || null,
      area: this.model.area.trim() || null,
      cityId: this.model.cityId,
      pincode: this.model.pincode.trim(),
      lat: pin.lat,
      lng: pin.lng,
      isDefault: this.model.isDefault,
    };
    this.saving.set(true);
    this.error.set(null);
    const existing = this.address();
    const call = existing ? this.api.updateAddress(existing.id, body) : this.api.createAddress(body);
    call.subscribe({
      next: (a) => {
        this.saving.set(false);
        this.toast.success(existing ? 'Address updated' : 'Address saved');
        this.saved.emit(a);
      },
      error: (err) => {
        this.saving.set(false);
        this.error.set(errorMessage(err, 'Could not save the address.'));
      },
    });
  }
}
