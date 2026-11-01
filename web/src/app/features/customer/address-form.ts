import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';

import { errorMessage } from '../../core/api/api';
import { CustomerApi } from '../../core/api/customer-api';
import { Address, AddressRequest, PincodeCheck } from '../../core/api/models';
import { CityStore } from '../../core/city/city-store';
import { distanceKm } from '../../shared/geo/india';
import { ToastService } from '../../core/ui/feedback';
import { Spinner } from '../../shared/ui/kit';
import { LatLng, MapPicker } from '../../shared/ui/map-picker';
import { NotifyMe } from '../../shared/ui/notify-me';

const LABELS = ['Home', 'Work', 'Other'];

/** Wait this long after the pin stops moving before asking which PIN code it is in. */
const PIN_SETTLE_MS = 350;

/**
 * Create / edit an address. The PIN code decides the city (it must be one we serve), and the map pin is required:
 * it drives the travel fee and the Genie's route. The pin must be in India (the map refuses anything else) and
 * within the city's service radius; the server checks both again on save.
 *
 * The two stay in step: typing a PIN code moves the pin to that PIN code's centre (then the user drags it to the
 * door), and moving the pin fills in the PIN code it lands in (GET /geo/pincodes/{pin}, GET /geo/pincode-at).
 *
 * The area name is not typed: the field is disabled and always follows the PIN code (typed or found from the map
 * pin), when adding and when editing.
 */
@Component({
  selector: 'pg-address-form',
  imports: [DecimalPipe, FormsModule, MapPicker, NotifyMe, Spinner],
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
        <label class="label" for="a-pin">PIN code</label>
        <div class="flex items-center gap-3">
          <input
            id="a-pin"
            name="pincode"
            class="field max-w-40"
            required
            inputmode="numeric"
            autocomplete="postal-code"
            pattern="[1-9][0-9]{5}"
            maxlength="6"
            [(ngModel)]="model.pincode"
            (ngModelChange)="onPincode($event, 'typed')"
            placeholder="380015"
          />
          @if (checkingPin()) {
            <pg-spinner [size]="18" />
          }
        </div>
        <p class="mt-1 min-h-5 text-sm" aria-live="polite">
          @if (pinCheck(); as c) {
            @if (c.serviceable) {
              <span class="font-medium text-emerald-700">{{ c.area ? c.area + ', ' : '' }}{{ c.cityName }}, {{ c.cityState }}</span>
            } @else {
              <span class="font-medium text-rose-700">We don't serve {{ c.pincode }} yet. We're live in {{ cities.liveNames() }}.</span>
            }
          } @else if (pinError()) {
            <span class="text-rose-700">{{ pinError() }}</span>
          } @else {
            <span class="text-muted">Your PIN code tells us your city.</span>
          }
        </p>
        @if (pinCheck(); as c) {
          @if (!c.serviceable) {
            <pg-notify-me [pincode]="c.pincode" />
          }
        }
      </div>

      <div>
        <span class="label">Pin the exact spot</span>
        <pg-map-picker [lat]="pin()?.lat ?? null" [lng]="pin()?.lng ?? null" [center]="mapCenter()" (moved)="onPinMoved($event)" />
        <p class="mt-1 flex min-h-5 items-center gap-2 text-sm" aria-live="polite" data-testid="pin-sync">
          @if (syncing()) {
            <pg-spinner [size]="14" />
            <span class="text-muted">{{ syncing() === 'pin' ? 'Finding this PIN code on the map…' : 'Finding the PIN code here…' }}</span>
          } @else if (syncNote(); as n) {
            <span [class]="n.tone === 'ok' ? 'text-emerald-700' : 'text-amber-700'">{{ n.text }}</span>
          }
        </p>
        @if (unservedPoint(); as p) {
          <p class="mt-1 text-sm text-rose-700">We don't serve this place yet.</p>
          <pg-notify-me [lat]="p.lat" [lng]="p.lng" />
        }
        @if (tooFar(); as far) {
          <p class="mt-1 text-sm font-medium text-rose-700" role="alert">
            The pin is {{ far.km | number: '1.0-0' }} km from {{ far.city }}; we serve up to {{ far.radius }} km. Move the pin or check the PIN code.
          </p>
        }
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
          <input
            id="a-area"
            name="area"
            class="field disabled:cursor-not-allowed disabled:bg-surface disabled:text-ink/70 disabled:opacity-100"
            disabled
            aria-describedby="a-area-hint"
            [value]="model.area"
            placeholder="From your PIN code"
          />
          <p id="a-area-hint" class="mt-1 text-xs text-muted">Filled in from the PIN code or the map pin.</p>
        </div>
        <div>
          <label class="label" for="a-landmark">Landmark (optional)</label>
          <input id="a-landmark" name="landmark" class="field" maxlength="120" [(ngModel)]="model.landmark" placeholder="Opp. Iscon Mall" />
        </div>
      </div>

      <fieldset class="rounded-2xl border border-line p-4" data-testid="address-contact">
        <legend class="px-1 text-sm font-semibold">Booking for someone else? <span class="font-normal text-muted">(optional)</span></legend>
        <p class="text-xs text-muted">
          Add the person who will be there, e.g. your parents. The Genie calls them, and they get an SMS with a tracking link and their own start code when
          the Genie sets off.
        </p>
        <div class="mt-3 grid gap-4 sm:grid-cols-2">
          <div>
            <label class="label" for="a-contact-name">Their name</label>
            <input
              id="a-contact-name"
              name="contactName"
              class="field"
              maxlength="80"
              autocomplete="off"
              [(ngModel)]="model.contactName"
              [required]="!!model.contactPhone.trim()"
              placeholder="Asha Shah"
            />
          </div>
          <div>
            <label class="label" for="a-contact-phone">Their mobile number</label>
            <input
              id="a-contact-phone"
              name="contactPhone"
              class="field"
              type="tel"
              inputmode="tel"
              maxlength="16"
              autocomplete="off"
              pattern="(\\+91[\\s-]?)?[6-9][0-9]{4}[\\s-]?[0-9]{5}"
              [(ngModel)]="model.contactPhone"
              [required]="!!model.contactName.trim()"
              placeholder="98250 12345"
            />
          </div>
        </div>
        @if (contactHalf()) {
          <p class="mt-2 text-xs text-amber-700">Add both their name and mobile number, or leave both empty.</p>
        }
      </fieldset>

      <label class="flex items-center gap-3 text-sm">
        <input type="checkbox" name="isDefault" class="size-5 rounded accent-brand" [(ngModel)]="model.isDefault" />
        Use as my default address
      </label>

      @if (error()) {
        <p class="rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ error() }}</p>
      }

      <div class="flex flex-col-reverse gap-2 pt-2 sm:flex-row sm:justify-end">
        <button type="button" class="btn-ghost" (click)="cancelled.emit()">Cancel</button>
        <button type="submit" class="btn-primary" [disabled]="saving() || !f.valid || !pin() || !pinCheck()?.serviceable || !!tooFar()">
          @if (saving()) {
            <pg-spinner />
          }
          {{ address() ? 'Save changes' : 'Save address' }}
        </button>
      </div>
      @if (!pinCheck()?.serviceable) {
        <p class="text-right text-xs text-muted">Enter a PIN code we serve to continue.</p>
      } @else if (!pin()) {
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
  protected readonly cities = inject(CityStore);

  protected readonly labels = LABELS;
  protected readonly pin = signal<LatLng | null>(null);
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly pinCheck = signal<PincodeCheck | null>(null);
  protected readonly checkingPin = signal(false);
  protected readonly pinError = signal<string | null>(null);
  /** A PIN code ↔ pin lookup in flight: 'pin' (placing a typed PIN code) or 'code' (PIN code of a moved pin). */
  protected readonly syncing = signal<'pin' | 'code' | null>(null);
  protected readonly syncNote = signal<{ text: string; tone: 'ok' | 'warn' } | null>(null);
  /** A pin outside every live city whose PIN code is unknown: "Notify me" for that spot. */
  protected readonly unservedPoint = signal<LatLng | null>(null);
  protected model = this.blank();

  /** The PIN code's city (from the live city list), else the city being browsed. */
  private readonly pinCity = computed(() => {
    const c = this.pinCheck();
    return c?.serviceable ? this.cities.byId(c.cityId) : null;
  });
  /** With no pin yet the map opens on the PIN code's area, else its city, else the browsed city. */
  protected readonly mapCenter = computed(() => {
    const c = this.pinCheck();
    if (c?.serviceable && c.lat !== null && c.lng !== null) return { lat: c.lat, lng: c.lng };
    const city = this.pinCity() ?? this.cities.city();
    return city?.lat != null && city.lng != null ? { lat: city.lat, lng: city.lng } : null;
  });
  /** Set when the pin is outside the PIN code city's service radius (the server would refuse it). */
  protected readonly tooFar = computed(() => {
    const city = this.pinCity();
    const pin = this.pin();
    if (!city || !pin || city.lat == null || city.lng == null) return null;
    const km = distanceKm(pin, { lat: city.lat, lng: city.lng });
    return km > city.radiusKm ? { km, city: city.name, radius: city.radiusKm } : null;
  });
  private pinSeq = 0;
  /** Bumped by every PIN code ↔ pin lookup; an answer that is no longer the latest is ignored. */
  private geoSeq = 0;
  private settleTimer: ReturnType<typeof setTimeout> | undefined;
  /** The PIN code the pin is known to be in (so typing that same PIN code does not move it). */
  private pinPincode: string | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.settleTimer));
    effect(() => {
      const a = this.address();
      untracked(() => {
        this.model = a
          ? {
              label: a.label,
              line1: a.line1,
              line2: a.line2 ?? '',
              landmark: a.landmark ?? '',
              area: a.area ?? '',
              pincode: a.pincode,
              isDefault: a.isDefault,
              contactName: a.contactName ?? '',
              contactPhone: a.contactPhone ?? '',
            }
          : this.blank();
        this.pin.set(a ? { lat: a.lat, lng: a.lng } : null);
        this.pinPincode = a?.pincode ?? null;
        this.error.set(null);
        this.syncNote.set(null);
        this.geoSeq++;
        this.onPincode(this.model.pincode, 'loaded');
      });
    });
  }

  private blank() {
    return { label: 'Home', line1: '', line2: '', landmark: '', area: '', pincode: '', isDefault: false, contactName: '', contactPhone: '' };
  }

  /**
   * Checks the PIN code as soon as it has 6 digits; the area always follows it (empty while the PIN code is not one
   * we serve). A PIN code the user typed also moves the pin there; one filled in from the map does not.
   */
  protected onPincode(value: string, from: 'typed' | 'map' | 'loaded'): void {
    const pincode = (value ?? '').trim();
    const seq = ++this.pinSeq;
    this.pinCheck.set(null);
    this.pinError.set(null);
    if (from !== 'loaded') this.setArea(null); // a new PIN code: the old area no longer applies
    if (from === 'typed') {
      this.unservedPoint.set(null);
      this.geoSeq++; // the user's typing wins over a map lookup still in flight
      clearTimeout(this.settleTimer);
      this.syncing.set(null);
      this.syncNote.set(null);
    }
    if (!/^[1-9][0-9]{5}$/.test(pincode)) {
      if (pincode.length === 6) this.pinError.set('Enter a valid 6-digit PIN code.');
      return;
    }
    if (from === 'typed' && pincode !== this.pinPincode) this.movePinTo(pincode);
    this.checkingPin.set(true);
    this.cities.checkPincode(pincode, false).subscribe({
      next: (c) => {
        if (seq !== this.pinSeq) return;
        this.checkingPin.set(false);
        this.pinCheck.set(c);
        // also for an address loaded for editing: the area is always the PIN code's
        this.setArea(c.serviceable ? c.area : null);
      },
      error: (err) => {
        if (seq !== this.pinSeq) return;
        this.checkingPin.set(false);
        this.pinError.set(errorMessage(err, 'Could not check this PIN code.'));
      },
    });
  }

  /** The area shown and saved: the first part of the PIN code's area name ("Lal Darwaja, Old City" → "Lal Darwaja"). */
  private setArea(area: string | null | undefined): void {
    this.model = { ...this.model, area: area ? area.split(',')[0].trim().slice(0, 80) : '' };
  }

  /** Typed PIN code → pin at its centre. */
  private movePinTo(pincode: string): void {
    const seq = ++this.geoSeq;
    this.syncing.set('pin');
    this.cities.locatePincode(pincode).subscribe({
      next: (r) => {
        if (seq !== this.geoSeq) return;
        this.syncing.set(null);
        if (r.found && r.lat !== null && r.lng !== null) {
          this.pin.set({ lat: r.lat, lng: r.lng });
          this.pinPincode = pincode;
          this.syncNote.set({ text: `Pin moved to ${pincode}${r.area ? ' · ' + r.area : ''}. Drag it to your exact door.`, tone: 'ok' });
        } else {
          this.syncNote.set({ text: `We couldn't find ${pincode} on the map. Tap the map to drop the pin.`, tone: 'warn' });
        }
      },
      error: () => {
        if (seq !== this.geoSeq) return;
        this.syncing.set(null);
        this.syncNote.set({ text: "Couldn't place this PIN code on the map. Tap the map to drop the pin.", tone: 'warn' });
      },
    });
  }

  /** Moved pin → the PIN code it is in, once the pin has settled. */
  protected onPinMoved(point: LatLng): void {
    this.pin.set(point);
    this.pinPincode = null;
    this.unservedPoint.set(null);
    const seq = ++this.geoSeq;
    clearTimeout(this.settleTimer);
    this.syncing.set('code');
    this.syncNote.set(null);
    this.settleTimer = setTimeout(() => {
      this.cities.pincodeAt(point.lat, point.lng).subscribe({
        next: (r) => {
          if (seq !== this.geoSeq) return;
          this.syncing.set(null);
          if (!r.inIndia) return; // the map already said so
          if (!r.pincode) {
            this.syncNote.set({ text: "We couldn't tell the PIN code here. Type it above.", tone: 'warn' });
            // outside every live city? then offer "Notify me" for this spot (Phase 3.5B)
            this.cities.checkLocation(point.lat, point.lng).subscribe({
              next: (l) => {
                if (seq === this.geoSeq && l.inIndia && !l.serviceable) this.unservedPoint.set(point);
              },
              error: () => undefined,
            });
            return;
          }
          this.pinPincode = r.pincode;
          if (r.pincode === this.model.pincode.trim()) {
            if (r.area && !this.model.area && this.pinCheck()?.serviceable) this.setArea(r.area);
            this.syncNote.set({ text: `The pin is in ${r.pincode}.`, tone: 'ok' });
            return;
          }
          this.model = { ...this.model, pincode: r.pincode };
          this.syncNote.set({ text: `PIN code ${r.pincode} filled in from the map. Change it if it's wrong.`, tone: 'ok' });
          this.onPincode(r.pincode, 'map');
        },
        error: () => {
          if (seq !== this.geoSeq) return;
          this.syncing.set(null);
          this.syncNote.set({ text: "Couldn't find the PIN code here. Type it above.", tone: 'warn' });
        },
      });
    }, PIN_SETTLE_MS);
  }

  /** Only one of the contact's name and phone filled in (the API wants both or neither). */
  protected contactHalf(): boolean {
    return !!this.model.contactName.trim() !== !!this.model.contactPhone.trim();
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
      pincode: this.model.pincode.trim(),
      lat: pin.lat,
      lng: pin.lng,
      isDefault: this.model.isDefault,
      contactName: this.model.contactName.trim() || null,
      contactPhone: this.model.contactPhone.trim() || null,
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
