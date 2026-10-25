package com.progenie.shared.ratelimit;

import java.time.Duration;

/**
 * Fixed-window rate limits (logins, one-time codes, geocoding lookups). Counters live in PostgreSQL by default
 * ({@link DatabaseRateLimiter}) or in Redis with {@code progenie.cache.type=redis} ({@link RedisRateLimiter});
 * both are shared by every API instance.
 */
public interface RateLimiter {

    /** Counts one hit; returns false once more than {@code limit} hits fall in the current window. */
    boolean tryAcquire(String key, int limit, Duration window);

    /** Counts one hit and returns the number of hits in the current window (including this one). */
    int hit(String key, Duration window);

    /** Forgets a key (e.g. failed logins after a successful one). */
    void reset(String key);
}
