import { ChangeDetectionStrategy, Component, DestroyRef, ElementRef, afterNextRender, computed, effect, inject, signal, untracked, viewChild } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import type * as Leaflet from 'leaflet';

import { API, errorMessage } from '../../core/api/api';
import { AdminApi } from '../../core/api/admin-api';
import { AdminCategory, AdminCity, DemandCell, DemandLayer, DemandMap } from '../../core/api/models';
import { BarList, ColumnChart } from '../../shared/ui/charts';
import { Spinner } from '../../shared/ui/kit';

/** What each kind of unmet demand means, in admin words. */
export const KIND_LABELS: Record<string, string> = {
  PINCODE_UNSERVED: 'PIN code we don’t serve',
  LOCATION_UNSERVED: 'Map pin outside our cities',
  NO_GENIE_LISTED: 'Page with no Genie in the city',
  INSTANT_NONE_FREE: 'Genie Now: nobody free',
  INSTANT_NO_GENIE: 'Genie Now: nobody accepted',
};

/** Why Genie Now found nobody, and what usually fixes it. */
export const REASON_LABELS: Record<string, string> = {
  NO_GENIE_FOR_SERVICE: 'No Genie offers the service in the city (recruit)',
  TOO_FAR: 'Too far: nobody can arrive within an hour (recruit nearby)',
  NONE_ONLINE: 'Nobody online (ask Genies to keep ProGenie open)',
  OUTSIDE_HOURS: 'Outside working hours (longer hours)',
  ALL_BUSY: 'All busy with other jobs (more Genies)',
  NOBODY_ACCEPTED: 'Asked, but nobody accepted',
};

/** Tooltip text is HTML: escape anything that came from data (area names are typed by admins). */
function esc(s: string): string {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!);
}

const WEEKDAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

function ensureLeafletCss(): void {
  if (document.getElementById('leaflet-css')) return;
  const link = document.createElement('link');
  link.id = 'leaflet-css';
  link.rel = 'stylesheet';
  link.href = 'leaflet.css';
  document.head.appendChild(link);
}

/**
 * /admin/demand — the demand heatmap (Phase 3D): where customers book, and where they wanted something we couldn't
 * give (unserved PIN codes and places, pages with no Genie, Genie Now with nobody free or nobody accepting).
 * Grouped by PIN code; filters for city, category, period, weekday and hour.
 */
@Component({
  selector: 'pg-admin-demand-page',
  imports: [DecimalPipe, FormsModule, BarList, ColumnChart, Spinner],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="text-2xl font-bold sm:text-3xl">Demand map</h1>
        <p class="mt-1 text-sm text-muted">Where customers book, and where they wanted something we couldn't give. Grouped by PIN code, India time.</p>
      </div>
      <div class="inline-flex rounded-full border border-line bg-white p-1" role="radiogroup" aria-label="Layer">
        @for (l of layers; track l.value) {
          <button
            type="button"
            role="radio"
            class="min-h-10 rounded-full px-4 text-sm font-semibold"
            [class]="layer() === l.value ? 'bg-navy text-white' : 'text-muted hover:text-ink'"
            [attr.aria-checked]="layer() === l.value"
            (click)="layer.set(l.value)"
          >
            {{ l.label }}
          </button>
        }
      </div>
    </div>

    <form class="card mt-5 grid grid-cols-2 gap-3 p-4 sm:grid-cols-3 lg:grid-cols-5" aria-label="Filters">
      <label class="text-sm">
        <span class="label">Period</span>
        <select class="field" name="days" [ngModel]="days()" (ngModelChange)="days.set(+$event)">
          <option [ngValue]="7">Last 7 days</option>
          <option [ngValue]="30">Last 30 days</option>
          <option [ngValue]="90">Last 90 days</option>
        </select>
      </label>
      <label class="text-sm">
        <span class="label">City</span>
        <select class="field" name="city" [ngModel]="cityId()" (ngModelChange)="cityId.set($event)">
          <option [ngValue]="null">All cities</option>
          @for (c of cities.value() ?? []; track c.cityId) {
            <option [ngValue]="c.cityId">{{ c.name }}</option>
          }
        </select>
      </label>
      <label class="text-sm">
        <span class="label">Category</span>
        <select class="field" name="category" [ngModel]="categoryId()" (ngModelChange)="categoryId.set($event)">
          <option [ngValue]="null">All categories</option>
          @for (c of categories.value() ?? []; track c.id) {
            <option [ngValue]="c.id">{{ c.name }}</option>
          }
        </select>
      </label>
      <label class="text-sm">
        <span class="label">Weekday</span>
        <select class="field" name="weekday" [ngModel]="weekday()" (ngModelChange)="weekday.set($event)">
          <option [ngValue]="null">Every day</option>
          @for (d of weekdays; track d; let i = $index) {
            <option [ngValue]="i + 1">{{ d }}</option>
          }
        </select>
      </label>
      <label class="text-sm">
        <span class="label">Hour</span>
        <select class="field" name="hour" [ngModel]="hour()" (ngModelChange)="hour.set($event)">
          <option [ngValue]="null">Any time</option>
          @for (h of hours; track h) {
            <option [ngValue]="h">{{ hourLabel(h) }}</option>
          }
        </select>
      </label>
    </form>

    @if (error()) {
      <p class="mt-4 rounded-xl bg-rose-50 px-4 py-3 text-sm text-rose-800" role="alert">{{ error() }}</p>
    }

    <div class="mt-5 grid grid-cols-[minmax(0,1fr)] gap-5 xl:grid-cols-[minmax(0,1fr)_22rem]">
      <section class="card overflow-hidden" aria-label="Map">
        <div class="flex flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-3">
          <p class="font-semibold" data-testid="demand-total">
            {{ data()?.total ?? 0 | number }} {{ layer() === 'BOOKINGS' ? 'bookings' : 'times we couldn’t help' }}
            @if (data()?.unplaced) {
              <span class="text-sm font-normal text-muted">· {{ data()!.unplaced }} without a place</span>
            }
          </p>
          @if (loading()) {
            <pg-spinner [size]="18" />
          }
        </div>
        <div class="relative">
          <div #map class="h-[26rem] w-full bg-surface sm:h-[32rem]" role="img" [attr.aria-label]="'Heatmap of ' + (layer() === 'BOOKINGS' ? 'bookings' : 'unmet demand')"></div>
          @if (!ready()) {
            <div class="absolute inset-0 grid place-items-center"><pg-spinner /></div>
          }
        </div>
        <p class="px-4 py-2 text-xs text-muted">Brighter = more. Tap a circle for the PIN code and counts.</p>
      </section>

      <aside class="space-y-5">
        @if (layer() === 'UNMET') {
          <section class="card p-5" aria-labelledby="kinds-h">
            <h2 id="kinds-h" class="font-bold">What people couldn't get</h2>
            <pg-bar-list class="mt-4" [items]="kindItems()" />
          </section>
          @if (reasonItems().length) {
            <section class="card p-5" aria-labelledby="reasons-h">
              <h2 id="reasons-h" class="font-bold">Why Genie Now found nobody</h2>
              <pg-bar-list class="mt-4" [items]="reasonItems()" />
            </section>
          }
        }
        <section class="card p-5" aria-labelledby="top-h">
          <h2 id="top-h" class="font-bold">Top places</h2>
          <ol class="mt-3 divide-y divide-line text-sm" data-testid="demand-top">
            @for (c of top(); track c.key) {
              <li class="flex items-center justify-between gap-3 py-2">
                <span class="min-w-0">
                  <span class="font-semibold">{{ c.pincode ?? 'Around ' + (c.lat | number: '1.2-2') + ', ' + (c.lng | number: '1.2-2') }}</span>
                  @if (c.area) {
                    <span class="text-muted"> · {{ c.area }}</span>
                  }
                  @if (c.lat === null) {
                    <span class="block text-xs text-muted">Not on the map (we don't know this PIN code's centre)</span>
                  }
                </span>
                <span class="shrink-0 font-display text-lg font-bold tabular-nums">{{ c.count }}</span>
              </li>
            } @empty {
              <li class="py-2 text-muted">Nothing for these filters.</li>
            }
          </ol>
        </section>
      </aside>
    </div>

    @if (data(); as d) {
      <div class="mt-5 grid gap-5 lg:grid-cols-2">
        <section class="card p-5">
          <pg-column-chart [points]="hourPoints()" title="By hour of day" labelHeader="Hour" />
          <p class="mt-2 text-xs text-muted">All days in the period, whatever the weekday and hour filters.</p>
        </section>
        <section class="card p-5">
          <pg-column-chart [points]="weekdayPoints()" title="By weekday" labelHeader="Weekday" />
        </section>
      </div>
    }
  `,
})
export default class DemandPage {
  private readonly api = inject(AdminApi);
  private readonly host = viewChild.required<ElementRef<HTMLElement>>('map');

  protected readonly layers: { value: DemandLayer; label: string }[] = [
    { value: 'BOOKINGS', label: 'Bookings' },
    { value: 'UNMET', label: 'Unmet demand' },
  ];
  protected readonly weekdays = WEEKDAYS;
  protected readonly hours = Array.from({ length: 24 }, (_, i) => i);

  readonly layer = signal<DemandLayer>('BOOKINGS');
  readonly days = signal(30);
  readonly cityId = signal<number | null>(null);
  readonly categoryId = signal<number | null>(null);
  readonly weekday = signal<number | null>(null);
  readonly hour = signal<number | null>(null);

  protected readonly cities = httpResource<AdminCity[]>(() => `${API}/admin/cities`);
  protected readonly categories = httpResource<AdminCategory[]>(() => `${API}/admin/categories`);
  readonly data = signal<DemandMap | null>(null);
  protected readonly loading = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly ready = signal(false);

  protected readonly top = computed(() => (this.data()?.cells ?? []).slice(0, 12));
  protected readonly kindItems = computed(() =>
    Object.entries(this.data()?.byKind ?? {})
      .sort((a, b) => b[1] - a[1])
      .map(([k, v]) => ({ label: KIND_LABELS[k] ?? k, value: v })),
  );
  protected readonly reasonItems = computed(() =>
    Object.entries(this.data()?.byReason ?? {})
      .sort((a, b) => b[1] - a[1])
      .map(([k, v]) => ({ label: REASON_LABELS[k] ?? k, value: v })),
  );
  protected readonly hourPoints = computed(() => (this.data()?.byHour ?? []).map((v, h) => ({ label: this.hourLabel(h), value: v })));
  protected readonly weekdayPoints = computed(() => (this.data()?.byWeekday ?? []).map((v, i) => ({ label: WEEKDAYS[i].slice(0, 3), value: v })));

  private L: typeof Leaflet | null = null;
  private map: Leaflet.Map | null = null;
  private heat: Leaflet.Layer | null = null;
  private markers: Leaflet.LayerGroup | null = null;

  constructor() {
    afterNextRender(() => void this.init());
    inject(DestroyRef).onDestroy(() => this.map?.remove());
    effect(() => {
      const filters = { layer: this.layer(), days: this.days(), cityId: this.cityId(), categoryId: this.categoryId(), weekday: this.weekday(), hour: this.hour() };
      untracked(() => this.load(filters));
    });
    effect(() => {
      const d = this.data();
      if (this.ready() && d) untracked(() => this.draw(d.cells));
    });
  }

  protected hourLabel(h: number): string {
    const suffix = h < 12 ? 'am' : 'pm';
    return `${h % 12 === 0 ? 12 : h % 12} ${suffix}`;
  }

  private load(f: Parameters<AdminApi['demand']>[0]): void {
    this.loading.set(true);
    this.error.set(null);
    this.api.demand(f).subscribe({
      next: (d) => {
        this.loading.set(false);
        this.data.set(d);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err, 'Could not load the demand map.'));
      },
    });
  }

  private async init(): Promise<void> {
    ensureLeafletCss();
    const mod = await import('leaflet');
    const L = ((mod as unknown as { default?: typeof Leaflet }).default ?? mod) as typeof Leaflet;
    // leaflet.heat is a classic plugin: it adds L.heatLayer to the global Leaflet
    (globalThis as unknown as { L: typeof Leaflet }).L = L;
    await import('leaflet.heat');
    this.L = L;
    const map = L.map(this.host().nativeElement, { zoomControl: true }).setView([23.03, 72.55], 11);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '&copy; OpenStreetMap' }).addTo(map);
    this.map = map;
    this.markers = L.layerGroup().addTo(map);
    this.ready.set(true);
    setTimeout(() => {
      map.invalidateSize();
      const d = this.data();
      if (d) this.draw(d.cells);
    }, 150);
  }

  private draw(cells: DemandCell[]): void {
    const L = this.L;
    const map = this.map;
    if (!L || !map || !this.markers) return;
    const placed = cells.filter((c) => c.lat !== null && c.lng !== null);
    const max = Math.max(1, ...placed.map((c) => c.count));
    if (this.heat) map.removeLayer(this.heat);
    const heatLayer = (L as unknown as { heatLayer: (p: [number, number, number][], o: object) => Leaflet.Layer }).heatLayer;
    this.heat = heatLayer(
      placed.map((c) => [c.lat!, c.lng!, c.count / max] as [number, number, number]),
      { radius: 30, blur: 22, maxZoom: 14, minOpacity: 0.35 },
    ).addTo(map);
    this.markers.clearLayers();
    const unmet = this.layer() === 'UNMET';
    for (const c of placed) {
      const kinds = Object.entries(c.kinds)
        .map(([k, v]) => `${esc(KIND_LABELS[k] ?? k)}: ${v}`)
        .join('<br>');
      L.circleMarker([c.lat!, c.lng!], {
        radius: 6 + 10 * Math.sqrt(c.count / max),
        color: unmet ? '#b45309' : '#4f46e5',
        weight: 1,
        fillOpacity: 0.15,
      })
        .bindTooltip(`<strong>${esc(c.pincode ?? 'Around here')}</strong>${c.area ? ' · ' + esc(c.area) : ''}<br>${c.count} ${unmet ? 'unmet' : 'bookings'}${kinds ? '<br>' + kinds : ''}`)
        .addTo(this.markers);
    }
    if (placed.length) {
      map.fitBounds(L.latLngBounds(placed.map((c) => [c.lat!, c.lng!] as [number, number])), { padding: [40, 40], maxZoom: 13, animate: false });
    }
  }
}
