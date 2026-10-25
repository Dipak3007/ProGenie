package com.progenie.shared.ratelimit;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Rate-limit counters in Redis ({@code progenie.cache.type=redis}): one key per limit, {@code progenie:rl:<key>},
 * incremented atomically and expiring one window after its first hit. No database write per login attempt.
 */
@Component
@ConditionalOnProperty(name = "progenie.cache.type", havingValue = "redis")
class RedisRateLimiter implements RateLimiter {

    /** INCR, and start the window on the first hit (atomic, so two requests can't both see "first"). */
    private static final RedisScript<Long> HIT = RedisScript.of("""
        local n = redis.call('INCR', KEYS[1])
        if n == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
        return n
        """, Long.class);

    private final StringRedisTemplate redis;

    RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean tryAcquire(String key, int limit, Duration window) {
        return hit(key, window) <= limit;
    }

    @Override
    public int hit(String key, Duration window) {
        Long n = redis.execute(HIT, List.of(redisKey(key)), String.valueOf(window.toMillis()));
        return n == null ? 1 : n.intValue();
    }

    @Override
    public void reset(String key) {
        redis.delete(redisKey(key));
    }

    private static String redisKey(String key) {
        return "progenie:rl:" + key;
    }
}
