import { DestroyRef, Injectable, effect, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Observable, Subject, filter, firstValueFrom, map } from 'rxjs';

import { API } from '../api/api';
import { AuthStore } from '../auth/auth-store';
import type { InstantOffer, InstantRequest, Tracking } from '../api/models';

/** What the server pushes. The payloads mostly say what changed (pages reload the details themselves); tracking carries the position. */
export interface LiveEvents {
  notification: { type: string; title: string; link: string | null };
  booking: { bookingId: string; change: string };
  ticket: { ticketId: string; bookingId: string | null; change: string };
  /** The Genie's latest position on the way to the customer (Phase 3C-10). */
  tracking: Tracking;
  /** Genie Now (3C-9), to the customer: their search changed (next Genie asked, booked, nobody free). */
  instant: InstantRequest;
  /** Genie Now, to a Genie: a new offer (the full offer), or `{offerId, change}` when it is gone. */
  instant_offer: InstantOffer | { offerId: number; change: 'EXPIRED' | 'WITHDRAWN' | 'TAKEN' };
  /** To a Genie: switched offline because ProGenie was closed for 2 minutes. */
  presence: { online: boolean };
}

type LiveMessage = { [K in keyof LiveEvents]: { type: K; data: LiveEvents[K] } }[keyof LiveEvents];

const BACKOFF_MS = [1_000, 3_000, 10_000, 30_000, 60_000];

/**
 * Live updates over Server-Sent Events from GET /api/v1/stream, while someone is logged in.
 *
 * Read with fetch() instead of EventSource because EventSource can't send the Authorization header (the access
 * token lives only in memory, never in a cookie or URL). Reconnects with back-off; the server ends each stream
 * after 10 minutes so the next one uses a fresh token. When the stream is down, {@link connected} is false and
 * the stores fall back to polling, so nothing breaks if a proxy doesn't pass the stream through.
 */
@Injectable({ providedIn: 'root' })
export class LiveStream {
  private readonly auth = inject(AuthStore);
  private readonly messages = new Subject<LiveMessage>();

  /** True while a stream is open and has said "ready". */
  readonly connected = signal(false);

  private abort: AbortController | null = null;
  private attempt = 0;
  private retryTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    effect(() => {
      if (this.auth.isLoggedIn()) {
        if (!this.abort) this.connect();
      } else {
        this.disconnect();
      }
    });
    inject(DestroyRef).onDestroy(() => this.disconnect());
  }

  /** Events of one type, e.g. on('booking'). */
  on<K extends keyof LiveEvents>(type: K): Observable<LiveEvents[K]> {
    return this.messages.pipe(
      filter((m) => m.type === type),
      map((m) => m.data as LiveEvents[K]),
    );
  }

  private async connect(): Promise<void> {
    const abort = new AbortController();
    this.abort = abort;
    try {
      let res = await this.open(abort.signal);
      if (res.status === 401) {
        // the access token expired: refresh it once (shares the interceptor's refresh) and try again
        await firstValueFrom(this.auth.refresh());
        res = await this.open(abort.signal);
      }
      if (!res.ok || !res.body) throw new Error(`stream ${res.status}`);
      await this.read(res.body, abort.signal);
    } catch {
      // network error, proxy without streaming, or logged out: handled below
    }
    if (this.abort !== abort) return; // replaced or stopped meanwhile
    this.connected.set(false);
    this.abort = null;
    if (this.auth.isLoggedIn()) this.scheduleReconnect();
  }

  private open(signal: AbortSignal): Promise<Response> {
    const token = this.auth.token();
    return fetch(`${API}/stream`, {
      headers: { Accept: 'text/event-stream', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      cache: 'no-store',
      signal,
    });
  }

  /** Minimal SSE parser: "event:" and "data:" lines, a blank line ends a message, ":" lines are pings. */
  private async read(body: ReadableStream<Uint8Array>, signal: AbortSignal): Promise<void> {
    const reader = body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let event = 'message';
    let data: string[] = [];
    while (!signal.aborted) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let nl: number;
      while ((nl = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, nl).replace(/\r$/, '');
        buffer = buffer.slice(nl + 1);
        if (line === '') {
          if (data.length) this.dispatch(event, data.join('\n'));
          event = 'message';
          data = [];
        } else if (line.startsWith('event:')) {
          event = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          data.push(line.slice(5).replace(/^ /, ''));
        }
      }
    }
  }

  private dispatch(event: string, raw: string): void {
    if (event === 'ready') {
      this.connected.set(true);
      this.attempt = 0;
      return;
    }
    try {
      this.messages.next({ type: event, data: JSON.parse(raw) } as LiveMessage);
    } catch {
      // ignore a malformed message
    }
  }

  private scheduleReconnect(): void {
    const delay = BACKOFF_MS[Math.min(this.attempt++, BACKOFF_MS.length - 1)];
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null;
      if (this.auth.isLoggedIn() && !this.abort) this.connect();
    }, delay);
  }

  private disconnect(): void {
    if (this.retryTimer) clearTimeout(this.retryTimer);
    this.retryTimer = null;
    this.abort?.abort();
    this.abort = null;
    this.attempt = 0;
    this.connected.set(false);
  }
}

/**
 * Reloads something when a live event matches, for the lifetime of the calling component:
 *   liveReload('booking', (e) => e.bookingId === this.id(), () => this.detail.reload());
 * Call it in a constructor or field initializer (it needs an injection context).
 */
export function liveReload<K extends keyof LiveEvents>(
  type: K,
  matches: (event: LiveEvents[K]) => boolean,
  reload: () => void,
): void {
  inject(LiveStream)
    .on(type)
    .pipe(filter(matches), takeUntilDestroyed())
    .subscribe(() => reload());
}
