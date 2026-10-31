import { Fix, LocationSharer, SEND_EVERY_MS } from './location-sharer';

/** A fake browser Geolocation that the test drives by hand. */
class FakeGeo {
  success: PositionCallback | null = null;
  failure: PositionErrorCallback | null = null;
  cleared: number[] = [];
  watchPosition(ok: PositionCallback, err?: PositionErrorCallback | null): number {
    this.success = ok;
    this.failure = err ?? null;
    return 7;
  }
  clearWatch(id: number): void {
    this.cleared.push(id);
  }
  getCurrentPosition(): void {}
  move(lat: number, lng: number, accuracy = 12): void {
    this.success!({ coords: { latitude: lat, longitude: lng, accuracy } } as GeolocationPosition);
  }
}

const settle = () => Promise.resolve().then(() => Promise.resolve());

describe('LocationSharer', () => {
  let geo: FakeGeo;
  let sent: Fix[];
  let sharer: LocationSharer;

  beforeEach(() => {
    vi.useFakeTimers();
    geo = new FakeGeo();
    sent = [];
    sharer = new LocationSharer(async (fix) => void sent.push(fix), geo as unknown as Geolocation);
  });
  afterEach(() => {
    sharer.stop();
    vi.useRealTimers();
  });

  it('sends the first fix at once, then at most one every 20 seconds (also when standing still)', async () => {
    sharer.start();
    geo.move(23.03, 72.56);
    await settle();
    expect(sent).toEqual([{ lat: 23.03, lng: 72.56, accuracyM: 12 }]);
    expect(sharer.accuracyM()).toBe(12);
    expect(sharer.lastSentAt()).not.toBeNull();

    geo.move(23.031, 72.561); // too soon: kept as the latest, not sent
    await settle();
    expect(sent.length).toBe(1);

    vi.advanceTimersByTime(SEND_EVERY_MS);
    await settle();
    expect(sent.at(-1)).toEqual({ lat: 23.031, lng: 72.561, accuracyM: 12 });

    vi.advanceTimersByTime(SEND_EVERY_MS); // no new fix: the same position is sent again so the map stays "live"
    await settle();
    expect(sent.length).toBe(3);
  });

  it('stops sending and releases the location watch when stopped', async () => {
    sharer.start();
    geo.move(23.03, 72.56);
    await settle();
    sharer.stop();
    expect(geo.cleared).toEqual([7]);
    expect(sharer.active()).toBe(false);
    vi.advanceTimersByTime(SEND_EVERY_MS * 3);
    await settle();
    expect(sent.length).toBe(1);
  });

  it('reports a blocked location and stops; a failed send is retried', async () => {
    sharer.start();
    geo.failure!({ code: 1, message: 'denied' } as GeolocationPositionError);
    expect(sharer.error()).toBe('denied');
    expect(sharer.active()).toBe(false);

    let fail = true;
    const flaky = new LocationSharer(async () => {
      if (fail) throw new Error('offline');
    }, geo as unknown as Geolocation);
    flaky.start();
    geo.move(23.03, 72.56);
    await settle();
    expect(flaky.error()).toBe('failed');
    fail = false;
    vi.advanceTimersByTime(SEND_EVERY_MS);
    await settle();
    expect(flaky.error()).toBeNull();
    expect(flaky.lastSentAt()).not.toBeNull();
    flaky.stop();
  });

  it('says so when the browser has no geolocation', () => {
    const none = new LocationSharer(async () => undefined, undefined);
    none.start();
    expect(none.error()).toBe('unsupported');
    expect(none.active()).toBe(false);
  });
});
