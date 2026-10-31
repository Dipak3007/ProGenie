import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  effect,
  inject,
  input,
  signal,
  viewChild,
} from '@angular/core';
import type * as Leaflet from 'leaflet';

import { Spinner } from './kit';
import type { LatLng } from '../geo/india';

/** Loads the Leaflet stylesheet once (same file as the map picker). */
function ensureLeafletCss(): void {
  if (document.getElementById('leaflet-css')) return;
  const link = document.createElement('link');
  link.id = 'leaflet-css';
  link.rel = 'stylesheet';
  link.href = 'leaflet.css';
  document.head.appendChild(link);
}

const HOME_PIN = `<svg width="34" height="42" viewBox="0 0 36 44" aria-hidden="true"><path d="M18 43s15-13.6 15-25A15 15 0 0 0 3 18c0 11.4 15 25 15 25Z" fill="#4f46e5" stroke="#fff" stroke-width="2"/><path d="M11 19.5 18 13l7 6.5V26h-4.5v-4h-5v4H11Z" fill="#fff"/></svg>`;
const GENIE_DOT = `<span style="display:block;width:22px;height:22px;border-radius:9999px;background:#f5b301;border:3px solid #fff;box-shadow:0 0 0 6px rgba(245,179,1,.3),0 2px 6px rgba(0,0,0,.3)"></span>`;

/**
 * Read-only map for live "on the way" tracking (Phase 3C-10): the customer's home pin and the Genie's latest
 * position (a gold dot). Leaflet is lazy-loaded, like the map picker. Moves the dot when the position changes and
 * keeps both points in view until the user pans or zooms the map themselves.
 */
@Component({
  selector: 'pg-tracking-map',
  imports: [Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    <div class="relative overflow-hidden rounded-2xl border border-line bg-surface">
      <div #map class="h-64 w-full sm:h-80" role="img" [attr.aria-label]="label()"></div>
      @if (!ready()) {
        <div class="absolute inset-0 grid place-items-center text-sm text-muted"><pg-spinner /></div>
      }
    </div>
  `,
})
export class TrackingMap {
  /** The Genie's latest position; null until the first one arrives. */
  readonly genie = input<LatLng | null>(null);
  /** The customer's address pin. */
  readonly home = input<LatLng | null>(null);
  readonly label = input('Map showing your Genie and your address');

  private readonly host = viewChild.required<ElementRef<HTMLElement>>('map');
  protected readonly ready = signal(false);

  private L: typeof Leaflet | null = null;
  private map: Leaflet.Map | null = null;
  private genieMarker: Leaflet.Marker | null = null;
  private homeMarker: Leaflet.Marker | null = null;
  /** Stops re-fitting once the user moves the map themselves. */
  private userMoved = false;

  constructor() {
    afterNextRender(() => void this.init());
    inject(DestroyRef).onDestroy(() => this.map?.remove());
    effect(() => {
      const genie = this.genie();
      const home = this.home();
      if (this.ready()) this.draw(genie, home);
    });
  }

  private async init(): Promise<void> {
    ensureLeafletCss();
    const mod = await import('leaflet');
    const L = ((mod as unknown as { default?: typeof Leaflet }).default ?? mod) as typeof Leaflet;
    this.L = L;
    const start = this.genie() ?? this.home() ?? { lat: 22.5, lng: 79 };
    const map = L.map(this.host().nativeElement, { zoomControl: true, attributionControl: true }).setView(start, 14);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '&copy; OpenStreetMap' }).addTo(map);
    map.on('dragstart', () => (this.userMoved = true));
    map.on('zoomstart', (e: Leaflet.LeafletEvent) => {
      // fitBounds also zooms; only count zooms started by the user (wheel, buttons, pinch)
      if ((e as unknown as { originalEvent?: Event }).originalEvent) this.userMoved = true;
    });
    this.map = map;
    this.ready.set(true);
    // The container may have had no size yet when first drawn; measure again and re-fit both points.
    setTimeout(() => {
      map.invalidateSize();
      this.draw(this.genie(), this.home());
    }, 150);
  }

  private draw(genie: LatLng | null, home: LatLng | null): void {
    const L = this.L;
    const map = this.map;
    if (!L || !map) return;
    if (home) {
      if (!this.homeMarker) {
        const icon = L.divIcon({ className: '', iconSize: [34, 42], iconAnchor: [17, 40], html: HOME_PIN });
        this.homeMarker = L.marker(home, { icon, keyboard: false, title: 'Your address' }).addTo(map);
      } else {
        this.homeMarker.setLatLng(home);
      }
    }
    if (genie) {
      if (!this.genieMarker) {
        const icon = L.divIcon({ className: '', iconSize: [22, 22], iconAnchor: [11, 11], html: GENIE_DOT });
        this.genieMarker = L.marker(genie, { icon, keyboard: false, title: 'Your Genie', zIndexOffset: 1000 }).addTo(map);
      } else {
        this.genieMarker.setLatLng(genie);
      }
    }
    if (this.userMoved) return;
    if (genie && home) {
      map.fitBounds(L.latLngBounds([genie, home]), { padding: [40, 40], maxZoom: 16, animate: false });
    } else if (genie ?? home) {
      map.setView((genie ?? home)!, 15, { animate: false });
    }
  }
}
