import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpClient, httpResource } from '@angular/common/http';
import { Observable, tap } from 'rxjs';

import { API } from '../api/api';
import { City, LocationCheck, PincodeAt, PincodeCheck, PincodeLocation } from '../api/models';

const STORAGE_KEY = 'pg.city';

/**
 * The city the visitor is browsing. Cities come from the server (GET /cities lists the live ones), so a new
 * city shows up in the switcher as soon as an admin switches it on: no code change.
 * The choice is remembered on this device (localStorage, when the browser allows it).
 */
@Injectable({ providedIn: 'root' })
export class CityStore {
  private readonly http = inject(HttpClient);

  /** Live cities, loaded once and shared by every component. */
  private readonly resource = httpResource<City[]>(() => `${API}/cities`, { defaultValue: [] });
  private readonly chosen = signal<string | null>(readStored());

  readonly cities = computed(() => this.resource.value() ?? []);
  readonly loading = computed(() => this.resource.isLoading());
  /** The chosen city, else the first live one (null only while loading or if nothing is live). */
  readonly city = computed<City | null>(() => {
    const list = this.cities();
    return list.find((c) => c.slug === this.chosen()) ?? list[0] ?? null;
  });
  readonly slug = computed(() => this.city()?.slug ?? null);
  readonly multiCity = computed(() => this.cities().length > 1);
  /** "Ahmedabad", "Ahmedabad and Vadodara", "Ahmedabad, Surat and Vadodara". */
  readonly liveNames = computed(() => joinNames(this.cities().map((c) => c.name)));

  /** Re-reads the live cities (e.g. after an admin switches one on or off). */
  reload(): void {
    this.resource.reload();
  }

  select(slug: string): void {
    this.chosen.set(slug);
    try {
      localStorage.setItem(STORAGE_KEY, slug);
    } catch {
      // storage blocked (private mode, previews): the choice lasts for this visit only
    }
  }

  bySlug(slug: string | null | undefined): City | null {
    return this.cities().find((c) => c.slug === slug) ?? null;
  }

  byId(id: number | null | undefined): City | null {
    return this.cities().find((c) => c.id === id) ?? null;
  }

  /** Is this PIN code served? If it is, that city becomes the current one. */
  checkPincode(pincode: string, switchCity = true): Observable<PincodeCheck> {
    return this.http.get<PincodeCheck>(`${API}/serviceability`, { params: { pincode } }).pipe(
      tap((r) => {
        if (switchCity && r.serviceable && r.citySlug) this.select(r.citySlug);
      }),
    );
  }

  /** Is this map pin in India, and inside a live city? */
  checkLocation(lat: number, lng: number): Observable<LocationCheck> {
    return this.http.get<LocationCheck>(`${API}/serviceability/location`, { params: { lat, lng } });
  }

  /** Where is this PIN code (its rough centre)? Our own data for served ones, else the map provider. Login needed. */
  locatePincode(pincode: string): Observable<PincodeLocation> {
    return this.http.get<PincodeLocation>(`${API}/geo/pincodes/${encodeURIComponent(pincode)}`);
  }

  /** Which PIN code is this map point in? `pincode` is null when nobody knows. Login needed. */
  pincodeAt(lat: number, lng: number): Observable<PincodeAt> {
    return this.http.get<PincodeAt>(`${API}/geo/pincode-at`, { params: { lat, lng } });
  }
}

function readStored(): string | null {
  try {
    return localStorage.getItem(STORAGE_KEY);
  } catch {
    return null;
  }
}

export function joinNames(names: string[]): string {
  if (names.length <= 1) return names[0] ?? '';
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`;
}
