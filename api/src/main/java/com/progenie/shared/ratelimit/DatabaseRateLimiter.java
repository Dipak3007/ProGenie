package com.progenie.shared.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fixed-window counters in PostgreSQL ({@code rate_limit_counters}), shared by every API replica: the default.
 * Each call is one upsert in its own transaction, so a hit still counts when the caller's request fails
 * (which is exactly when it matters: wrong passwords, rejected codes). With {@code progenie.cache.type=redis} the
 * counters live in Redis instead ({@link RedisRateLimiter}).
 */
@Component
@ConditionalOnExpression("'${progenie.cache.type:memory}' != 'redis'")
class DatabaseRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRateLimiter.class);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final JobLock lock;

    DatabaseRateLimiter(JdbcClient jdbc, Clock clock, JobLock lock) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.lock = lock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryAcquire(String key, int limit, Duration window) {
        return hit(key, window) <= limit;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int hit(String key, Duration window) {
        Integer hits = jdbc.sql(QueryUtil.get(QueryConstants.UPSERT_RATE_LIMIT_HIT))
            .param("key", key).param("start", Times.odt(windowStart(window)))
            .query(Integer.class).single();
        return hits;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reset(String key) {
        jdbc.sql(QueryUtil.get(QueryConstants.DELETE_RATE_LIMIT_KEY)).param("key", key).update();
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
        int rows = jdbc.sql(QueryUtil.get(QueryConstants.DELETE_RATE_LIMIT_OLD_WINDOWS)).update();
        if (rows > 0) {
            log.debug("Purged {} rate-limit windows", rows);
        }
    }
}
