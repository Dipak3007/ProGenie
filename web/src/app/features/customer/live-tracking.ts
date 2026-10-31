import { ChangeDetectionStrategy, Component, DestroyRef, computed, effect, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, DecimalPipe } from '@angular/common';
import { filter } from 'rxjs';
import { LucideDynamicIcon, LucideNavigation as Navigation, LucideWifiOff as WifiOff } from '@lucide/angular';

import { CustomerApi } from '../../core/api/customer-api';
import { Tracking } from '../../core/api/models';
import { LiveStream } from '../../core/live/live-stream';
import { TrackingMap } from '../../shared/ui/tracking-map';

/** A position older than this is shown as "last seen" (the Genie's page sends one about every 20 seconds). */
export const STALE_AFTER_MS = 2 * 60_000;
/** Fallback refresh when the live stream is down. */
export const POLL_MS = 30_000;
const TICK_MS = 15_000;

/**
 * The customer's live view of a Genie on the way (Phase 3C-10): a map with the Genie's latest position and the
 * arrival estimate. Updates arrive over the live stream ("tracking" event); while the stream is down it polls.
 * When the Genie's page stops sending, it says "last seen N min ago" instead of pretending to be live.
 */
@Component({
  selector: 'pg-live-tracking',
  imports: [DatePipe, DecimalPipe, LucideDynamicIcon, TrackingMap],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'block' },
  template: `
    @if (tracking(); as t) {
      <div class="space-y-3">
        @if (t.lat !== null && t.lng !== null) {
          <div class="flex flex-wrap items-end justify-between gap-x-4 gap-y-1">
            <p class="font-display text-2xl font-bold" aria-live="polite" data-testid="eta">
              @if (t.etaMinutes === 0) {
                {{ firstName() }} has arrived nearby
              } @else {
                About {{ t.etaMinutes }} min away
              }
            </p>
            <p class="text-sm text-muted">
              {{ t.distanceKm | number: '1.1-1' }} km{{ t.distanceMethod === 'ESTIMATE' ? ' (approx.)' : ' by road' }}
            </p>
          </div>
          @if (stale()) {
            <p class="flex items-start gap-2 rounded-xl bg-amber-50 px-3 py-2 text-sm text-amber-900" role="status" data-testid="last-seen">
              <svg [lucideIcon]="WifiOff" [size]="16" class="mt-0.5 shrink-0" aria-hidden="true"></svg>
              <span>Last seen {{ ageMinutes() }} min ago. {{ firstName() }}'s app may be closed or offline, so the estimate may be out of date.</span>
            </p>
          } @else {
            <p class="text-xs text-muted" data-testid="updated">Live · updated {{ ageMinutes() < 1 ? 'just now' : ageMinutes() + ' min ago' }}</p>
          }
        } @else {
          <p class="flex items-center gap-2 text-sm text-ink/75" data-testid="waiting">
            <svg [lucideIcon]="Navigation" [size]="16" class="text-sky-700" aria-hidden="true"></svg>
            {{ firstName() }} set off{{ t.travelStartedAt ? ' at ' + (t.travelStartedAt | date: 'h:mm a') : '' }}. Waiting for their location…
          </p>
        }
        <pg-tracking-map [genie]="genie()" [home]="home()" [label]="'Map showing ' + firstName() + ' and your address'" />
        <p class="text-xs text-muted">Only {{ firstName() }}'s latest position is shared, only until the job starts. No route history is kept.</p>
      </div>
    } @else if (error()) {
      <p class="text-sm text-muted">The map isn't available right now. {{ firstName() }} is still on the way.</p>
    }
  `,
})
export class LiveTracking {
  readonly bookingId = input.required<string>();
  readonly genieName = input('Your Genie');
  /** The trip is over (the job started or was cancelled); the page reloads the booking. */
  readonly ended = output<void>();

  private readonly api = inject(CustomerApi);
  private readonly live = inject(LiveStream);

  protected readonly tracking = signal<Tracking | null>(null);
  protected readonly error = signal(false);
  private readonly now = signal(Date.now());

  protected readonly firstName = computed(() => this.genieName().split(' ')[0] || 'Your Genie');
  protected readonly genie = computed(() => {
    const t = this.tracking();
    return t?.lat != null && t.lng != null ? { lat: t.lat, lng: t.lng } : null;
  });
  protected readonly home = computed(() => {
    const t = this.tracking();
    return t?.customerLat != null && t.customerLng != null ? { lat: t.customerLat, lng: t.customerLng } : null;
  });
  protected readonly ageMinutes = computed(() => {
    const at = this.tracking()?.updatedAt;
    return at ? Math.max(0, Math.floor((this.now() - new Date(at).getTime()) / 60_000)) : 0;
  });
  readonly stale = computed(() => {
    const at = this.tracking()?.updatedAt;
    return !!at && this.now() - new Date(at).getTime() > STALE_AFTER_MS;
  });

  constructor() {
    this.live
      .on('tracking')
      .pipe(filter((t) => t.bookingId === this.bookingId()), takeUntilDestroyed())
      .subscribe((t) => this.apply(t));

    effect(() => {
      this.bookingId(); // (re)load when the booking changes
      this.load();
    });

    const tick = setInterval(() => this.now.set(Date.now()), TICK_MS);
    const poll = setInterval(() => {
      if (!this.live.connected()) this.load();
    }, POLL_MS);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(tick);
      clearInterval(poll);
    });
  }

  private load(): void {
    this.api.tracking(this.bookingId()).subscribe({
      next: (t) => this.apply(t),
      error: () => this.error.set(true),
    });
  }

  private apply(t: Tracking): void {
    // A slow GET can land after a newer live update; never go back to an older position.
    const current = this.tracking();
    if (t.active && current?.updatedAt && (!t.updatedAt || new Date(t.updatedAt) < new Date(current.updatedAt))) return;
    this.error.set(false);
    this.now.set(Date.now());
    this.tracking.set(t);
    if (!t.active) this.ended.emit();
  }

  protected readonly Navigation = Navigation;
  protected readonly WifiOff = WifiOff;
}
