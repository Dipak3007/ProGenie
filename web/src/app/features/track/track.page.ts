import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { LucideDynamicIcon, LucideNavigation as Navigation, LucideWrench as Wrench } from '@lucide/angular';

import { API, errorCode } from '../../core/api/api';
import { ContactTracking } from '../../core/api/models';
import { TrackingMap } from '../../shared/ui/tracking-map';
import { Spinner } from '../../shared/ui/kit';

/** How often the page asks again (no login, so no live stream; the Genie's page sends about every 20 s). */
export const TRACK_POLL_MS = 20_000;
/** A position older than this is shown as "last seen". */
const STALE_AFTER_MS = 2 * 60_000;

/**
 * Public tracking page for the person at the address when a customer booked for someone else (Phase 3.5A). Opened
 * from the SMS/WhatsApp link: no login, the token in the link is the key. Shows the Genie on the way (or working)
 * and stops working when the job is done or cancelled.
 */
@Component({
  selector: 'pg-track-page',
  imports: [DecimalPipe, LucideDynamicIcon, Spinner, TrackingMap],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="mx-auto max-w-xl px-4 py-8 sm:py-12">
      @if (data(); as t) {
        <p class="text-sm font-semibold text-brand">{{ t.serviceName }} · ProGenie</p>
        @if (t.status === 'IN_PROGRESS') {
          <h1 class="mt-1 flex items-center gap-2 text-2xl font-bold sm:text-3xl" data-testid="track-status">
            <svg [lucideIcon]="Wrench" [size]="26" class="text-brand" aria-hidden="true"></svg> {{ t.genieName }} has started the job
          </h1>
          <p class="mt-2 text-ink/75">The map is switched off now. You'll be done soon.</p>
        } @else {
          <h1 class="mt-1 flex items-center gap-2 text-2xl font-bold sm:text-3xl" data-testid="track-status">
            <svg [lucideIcon]="Navigation" [size]="26" class="text-sky-700" aria-hidden="true"></svg> {{ t.genieName }} is on the way
          </h1>
          @if (t.lat !== null && t.lng !== null) {
            <p class="mt-2 font-display text-xl font-bold" aria-live="polite" data-testid="track-eta">
              {{ t.etaMinutes === 0 ? 'Arriving now' : 'About ' + t.etaMinutes + ' min away' }}
              <span class="text-sm font-normal text-muted">· {{ t.distanceKm | number: '1.1-1' }} km</span>
            </p>
            @if (stale()) {
              <p class="mt-2 rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900" role="status">
                Last seen {{ ageMinutes() }} min ago, so the estimate may be out of date.
              </p>
            }
          } @else {
            <p class="mt-2 text-ink/75">Waiting for {{ t.genieName }}'s location…</p>
          }
          <pg-tracking-map class="mt-4 block" [genie]="genie()" [home]="home()" [label]="'Map showing ' + t.genieName + ' and the address'" />
          <div class="mt-5 rounded-2xl bg-brand-mist/40 p-4 text-sm">
            <p class="font-semibold">When {{ t.genieName }} arrives</p>
            <p class="mt-1 text-ink/80">Share the 4-digit start code from your SMS only when they're at the door. Never share it before.</p>
          </div>
        }
        <p class="mt-4 text-xs text-muted">Only the Genie's latest position is shared, only until the job starts. This link stops working when the job is done.</p>
      } @else if (expired()) {
        <h1 class="text-2xl font-bold">This tracking link has expired</h1>
        <p class="mt-2 text-ink/75">The job has finished or was cancelled. The person who booked it can see the details in their ProGenie account.</p>
      } @else if (failed()) {
        <h1 class="text-2xl font-bold">We couldn't load the map</h1>
        <p class="mt-2 text-ink/75">Check your connection; this page tries again by itself.</p>
      } @else {
        <div class="flex justify-center py-16"><pg-spinner [size]="28" /></div>
      }
    </section>
  `,
})
export default class TrackPage {
  /** The :token route param (withComponentInputBinding). */
  readonly token = input.required<string>();

  private readonly http = inject(HttpClient);
  protected readonly data = signal<ContactTracking | null>(null);
  protected readonly expired = signal(false);
  protected readonly failed = signal(false);
  private readonly now = signal(Date.now());

  protected readonly genie = computed(() => {
    const t = this.data();
    return t?.lat != null && t.lng != null ? { lat: t.lat, lng: t.lng } : null;
  });
  protected readonly home = computed(() => {
    const t = this.data();
    return t?.homeLat != null && t.homeLng != null ? { lat: t.homeLat, lng: t.homeLng } : null;
  });
  protected readonly ageMinutes = computed(() => {
    const at = this.data()?.updatedAt;
    return at ? Math.max(0, Math.floor((this.now() - new Date(at).getTime()) / 60_000)) : 0;
  });
  protected readonly stale = computed(() => {
    const at = this.data()?.updatedAt;
    return !!at && this.now() - new Date(at).getTime() > STALE_AFTER_MS;
  });

  constructor() {
    queueMicrotask(() => this.load());
    const timer = setInterval(() => {
      this.now.set(Date.now());
      if (!this.expired()) this.load();
    }, TRACK_POLL_MS);
    inject(DestroyRef).onDestroy(() => clearInterval(timer));
  }

  load(): void {
    this.http.get<ContactTracking>(`${API}/track/${encodeURIComponent(this.token())}`).subscribe({
      next: (t) => {
        this.failed.set(false);
        this.data.set(t);
      },
      error: (err) => {
        if (errorCode(err) === 'LINK_EXPIRED') {
          this.data.set(null);
          this.expired.set(true);
        } else {
          this.failed.set(true);
        }
      },
    });
  }

  protected readonly Navigation = Navigation;
  protected readonly Wrench = Wrench;
}
