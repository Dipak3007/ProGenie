import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import { DecimalPipe } from '@angular/common';
import type * as Leaflet from 'leaflet';
import { LucideDynamicIcon, LucideLocateFixed as LocateFixed } from '@lucide/angular';

import { Spinner } from './kit';

export interface LatLng {
  lat: number;
  lng: number;
}

/** Centre of Ahmedabad, used until the user picks a point. */
export const AHMEDABAD: LatLng = { lat: 23.0225, lng: 72.5714 };

/** Loads the Leaflet stylesheet once, only when a map is first shown (it is built as a separate, non-injected bundle). */
function ensureLeafletCss(): void {
  if (document.getElementById('leaflet-css')) return;
  const link = document.createElement('link');
  link.id = 'leaflet-css';
  link.rel = 'stylesheet';
  link.href = 'leaflet.css';
  document.head.appendChild(link);
}

/**
 * Tap-to-pin map (OpenStreetMap + Leaflet, no API key). Leaflet is lazy-loaded, so pages without a map
 * never download it. The pin can be dragged, or set from the device's GPS.
 */
@Component({
  selector: 'pg-map-picker',
  imports: [DecimalPipe, LucideDynamicIcon, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <div class="relative overflow-hidden rounded-2xl border border-line bg-surface">
      <div #map class="h-64 w-full sm:h-72" role="application" [attr.aria-label]="label()"></div>
      @if (!ready()) {
        <div class="absolute inset-0 grid place-items-center text-sm text-muted"><pg-spinner /></div>
      }
      <button
        type="button"
        class="absolute right-3 top-3 z-[500] inline-flex min-h-10 items-center gap-2 rounded-full bg-white px-3 text-sm font-semibold text-ink shadow-lg ring-1 ring-line hover:text-brand"
        (click)="locate()"
        [disabled]="locating()"
      >
        @if (locating()) {
          <pg-spinner [size]="16" />
        } @else {
          <svg [lucideIcon]="LocateFixed" [size]="16" aria-hidden="true"></svg>
        }
        Use my location
      </button>
    </div>
    <p class="mt-2 text-xs text-muted">
      @if (lat() !== null && lng() !== null) {
        Pin at {{ lat() | number: '1.5-5' }}, {{ lng() | number: '1.5-5' }} · drag the pin or tap the map to adjust.
      } @else {
        Tap the map to drop a pin on the exact spot.
      }
      @if (geoError()) {
        <span class="block text-rose-700">{{ geoError() }}</span>
      }
    </p>
  `,
})
export class MapPicker {
  readonly lat = input<number | null>(null);
  readonly lng = input<number | null>(null);
  readonly label = input('Map: tap to set the location');
  readonly moved = output<LatLng>();

  private readonly host = viewChild.required<ElementRef<HTMLDivElement>>('map');
  private L: typeof Leaflet | null = null;
  private map: Leaflet.Map | null = null;
  private marker: Leaflet.Marker | null = null;

  protected readonly ready = signal(false);
  protected readonly locating = signal(false);
  protected readonly geoError = signal<string | null>(null);

  constructor() {
    afterNextRender(() => this.init());
    inject(DestroyRef).onDestroy(() => this.map?.remove());

    // Keep the pin in sync when the parent changes the coordinates (e.g. editing another address).
    effect(() => {
      const lat = this.lat();
      const lng = this.lng();
      untracked(() => {
        if (this.map && lat !== null && lng !== null) this.place({ lat, lng }, false);
      });
    });
  }

  private async init(): Promise<void> {
    ensureLeafletCss();
    const mod = await import('leaflet');
    const L = ((mod as unknown as { default?: typeof Leaflet }).default ?? mod) as typeof Leaflet;
    this.L = L;
    const start = this.lat() !== null && this.lng() !== null ? { lat: this.lat()!, lng: this.lng()! } : null;
    const map = L.map(this.host().nativeElement, { zoomControl: true, attributionControl: true }).setView(start ?? AHMEDABAD, start ? 16 : 12);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; OpenStreetMap',
    }).addTo(map);
    map.on('click', (e: Leaflet.LeafletMouseEvent) => this.place(e.latlng, true));
    this.map = map;
    if (start) this.place(start, false);
    this.ready.set(true);
    // The container may have been measured while hidden (dialogs); fix tiles once visible.
    setTimeout(() => map.invalidateSize(), 150);
  }

  private place(point: LatLng, emit: boolean): void {
    const L = this.L;
    if (!L || !this.map) return;
    const rounded = { lat: +point.lat.toFixed(6), lng: +point.lng.toFixed(6) };
    if (!this.marker) {
      const icon = L.divIcon({
        className: '',
        iconSize: [36, 44],
        iconAnchor: [18, 42],
        html: `<svg width="36" height="44" viewBox="0 0 36 44" aria-hidden="true"><path d="M18 43s15-13.6 15-25A15 15 0 0 0 3 18c0 11.4 15 25 15 25Z" fill="#4f46e5" stroke="#fff" stroke-width="2"/><circle cx="18" cy="18" r="6" fill="#f5b301"/></svg>`,
      });
      this.marker = L.marker(rounded, { draggable: true, icon, keyboard: true, title: 'Location pin' }).addTo(this.map);
      this.marker.on('dragend', () => {
        const p = this.marker!.getLatLng();
        this.moved.emit({ lat: +p.lat.toFixed(6), lng: +p.lng.toFixed(6) });
      });
    } else {
      this.marker.setLatLng(rounded);
    }
    if (emit) {
      this.moved.emit(rounded);
    } else {
      this.map.setView(rounded, Math.max(this.map.getZoom(), 15));
    }
  }

  protected locate(): void {
    if (!('geolocation' in navigator)) {
      this.geoError.set('This browser cannot share its location.');
      return;
    }
    this.locating.set(true);
    this.geoError.set(null);
    navigator.geolocation.getCurrentPosition(
      (pos) => {
        this.locating.set(false);
        const point = { lat: pos.coords.latitude, lng: pos.coords.longitude };
        this.place(point, true);
        this.map?.setView(point, 17);
      },
      (err) => {
        this.locating.set(false);
        this.geoError.set(err.code === err.PERMISSION_DENIED ? 'Location permission was denied. Tap the map instead.' : 'Could not get your location. Tap the map instead.');
      },
      { enableHighAccuracy: true, timeout: 10000 },
    );
  }

  protected readonly LocateFixed = LocateFixed;
}
