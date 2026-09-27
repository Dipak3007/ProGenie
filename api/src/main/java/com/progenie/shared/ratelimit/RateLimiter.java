package com.progenie.shared.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixed-window counters in PostgreSQL ({@code rate_limit_counters}), shared by every API replica.
 * Each call is one upsert in its own transaction, so a hit still counts when the caller's request fails
 * (which is exactly when it matters: wrong passwords, rejected codes). Redis can replace this later.
 */
@Component
public class RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final JobLock lock;

    public RateLimiter(JdbcClient jdbc, Clock clock, JobLock lock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.lock = lock;
    }

    /** Counts one hit; returns false once more than {@code limit} hits fall in the current window. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquire(String key, int limit, Duration window) {
        return hit(key, window) <= limit;
    }

    /** Counts one hit and returns the number of hits in the current window (including this one). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int hit(String key, Duration window) {
        Integer hits = jdbc.sql("""
                INSERT INTO rate_limit_counters (key, window_start, hits) VALUES (:key, :start, 1)
                ON CONFLICT (key, window_start) DO UPDATE SET hits = rate_limit_counters.hits + 1
                RETURNING hits
                """)
            .param("key", key).param("start", Times.odt(windowStart(window)))
            .query(Integer.class).single();
        return hits;
    }

    /** Forgets a key (e.g. failed logins after a successful one). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(String key) {
        jdbc.sql("DELETE FROM rate_limit_counters WHERE key = :key").param("key", key).update();
    }

    Instant windowStart(Duration window) {
        long size = window.toMillis();
        long now = clock.millis();
        return Instant.ofEpochMilli(now - Math.floorMod(now, size));
    }

    /** Old windows are useless; drop them hourly. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    @Transactional
    public void purgeOldWindows() {
        if (!lock.tryAcquire("rate-limit-purge")) {
            return;
        }
        int rows = jdbc.sql("DELETE FROM rate_limit_counters WHERE window_start < now() - interval '1 day'").update();
        if (rows > 0) {
            log.debug("Purged {} rate-limit windows", rows);
        }
    }
}
