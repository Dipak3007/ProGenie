import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';

import { AuthStore } from '../auth/auth-store';
import { LiveEvents, LiveStream } from './live-stream';

/** A fetch() response whose body streams the given SSE text in small chunks (as a proxy might split it). */
function sseResponse(text: string): Response {
  const bytes = new TextEncoder().encode(text);
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (let i = 0; i < bytes.length; i += 7) controller.enqueue(bytes.slice(i, i + 7));
      // stays open like a real stream; the test aborts it by logging out
    },
  });
  return new Response(stream, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

describe('LiveStream', () => {
  const realFetch = globalThis.fetch;
  let calls: { url: string; auth: string | null }[];

  beforeEach(() => {
    calls = [];
    TestBed.configureTestingModule({ providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()] });
  });
  afterEach(() => {
    globalThis.fetch = realFetch;
  });

  it('opens the stream with the token once logged in and delivers typed events', async () => {
    globalThis.fetch = ((url: string, init?: RequestInit) => {
      calls.push({ url, auth: new Headers(init?.headers).get('Authorization') });
      return Promise.resolve(
        sseResponse(
          'event:ready\nretry:3000\ndata:{"role":"GENIE"}\n\n' +
            ':ping\n\n' +
            'event:booking\ndata:{"bookingId":"b1","change":"REQUESTED"}\n\n' +
            'event:notification\r\ndata:{"type":"BOOKING_REQUESTED","title":"New booking request","link":null}\r\n\r\n',
        ),
      );
    }) as typeof fetch;

    const live = TestBed.inject(LiveStream);
    const bookings: LiveEvents['booking'][] = [];
    const notes: LiveEvents['notification'][] = [];
    live.on('booking').subscribe((e) => bookings.push(e));
    live.on('notification').subscribe((e) => notes.push(e));

    TestBed.inject(AuthStore).applyLogin('token-123', {
      id: 'g1', fullName: 'Ravi Patel', email: null, phone: '9825000001', role: 'GENIE',
      phoneVerified: true, emailVerified: false, pendingConsents: [], deletionScheduledFor: null,
    });
    TestBed.tick();
    await new Promise((r) => setTimeout(r, 50));

    expect(calls).toEqual([{ url: '/api/v1/stream', auth: 'Bearer token-123' }]);
    expect(live.connected()).toBe(true);
    expect(bookings).toEqual([{ bookingId: 'b1', change: 'REQUESTED' }]);
    expect(notes.map((n) => n.type)).toEqual(['BOOKING_REQUESTED']);
  });

  it('stays disconnected while nobody is logged in', () => {
    globalThis.fetch = (() => {
      throw new Error('should not be called');
    }) as typeof fetch;
    const live = TestBed.inject(LiveStream);
    TestBed.tick();
    expect(live.connected()).toBe(false);
  });
});
