import { signal } from '@angular/core';

/** The Genie's page sends a position at most this often while on the way. */
export const SEND_EVERY_MS = 20_000;

export type ShareError = 'denied' | 'unavailable' | 'unsupported' | 'failed';

export interface Fix {
  lat: number;
  lng: number;
  accuracyM: number | null;
}

/**
 * Shares the Genie's position while a booking is ON_THE_WAY (Phase 3C-10). Watches the device location and sends
 * the latest fix at most every {@link SEND_EVERY_MS}: the first one at once, then one per interval (also when the
 * Genie is standing still, so the customer's map keeps saying "live"). Nothing is sent once {@link stop} is called,
 * and the browser's location watch is released.
 *
 * Plain class (not a service) so a page owns exactly one per job and stops it when the status changes or the page
 * closes. `send` returns a promise; it is not called again while one is still in flight.
 */
export class LocationSharer {
  /** Watching and sending. */
  readonly active = signal(false);
  /** When the last position was accepted by the server. */
  readonly lastSentAt = signal<Date | null>(null);
  readonly error = signal<ShareError | null>(null);
  /** Accuracy (m) of the latest fix; a laptop without GPS reports tens of km. */
  readonly accuracyM = signal<number | null>(null);

  private watchId: number | null = null;
  private timer: ReturnType<typeof setInterval> | null = null;
  private latest: Fix | null = null;
  private sentAt = 0;
  private sending = false;

  constructor(
    private readonly send: (fix: Fix) => Promise<void>,
    private readonly geo: Geolocation | undefined = typeof navigator !== 'undefined' ? navigator.geolocation : undefined,
  ) {}

  start(): void {
    if (this.active()) return;
    if (!this.geo) {
      this.error.set('unsupported');
      return;
    }
    this.error.set(null);
    this.active.set(true);
    this.watchId = this.geo.watchPosition(
      (p) => {
        this.latest = { lat: p.coords.latitude, lng: p.coords.longitude, accuracyM: Number.isFinite(p.coords.accuracy) ? Math.round(p.coords.accuracy) : null };
        this.accuracyM.set(this.latest.accuracyM);
        if (this.error() !== 'failed') this.error.set(null);
        void this.flush(false);
      },
      (err) => {
        this.error.set(err.code === 1 ? 'denied' : 'unavailable');
        if (err.code === 1) this.stop(false);
      },
      { enableHighAccuracy: true, maximumAge: 10_000, timeout: 30_000 },
    );
    this.timer = setInterval(() => void this.flush(false), SEND_EVERY_MS);
  }

  /** Stops watching. `clearError` false keeps the reason visible (location denied). */
  stop(clearError = true): void {
    if (this.watchId !== null) this.geo?.clearWatch(this.watchId);
    if (this.timer) clearInterval(this.timer);
    this.watchId = null;
    this.timer = null;
    this.latest = null;
    this.active.set(false);
    if (clearError) this.error.set(null);
  }

  /** Sends the latest fix if the interval has passed (or `force`). */
  async flush(force: boolean): Promise<void> {
    const fix = this.latest;
    if (!fix || !this.active() || this.sending) return;
    if (!force && Date.now() - this.sentAt < SEND_EVERY_MS - 500) return;
    this.sending = true;
    try {
      await this.send(fix);
      this.sentAt = Date.now();
      this.lastSentAt.set(new Date(this.sentAt));
      if (this.error() === 'failed') this.error.set(null);
    } catch {
      this.error.set('failed');
    } finally {
      this.sending = false;
    }
  }
}
