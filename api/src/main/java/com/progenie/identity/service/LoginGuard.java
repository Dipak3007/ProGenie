package com.progenie.identity.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.progenie.shared.ratelimit.RateLimiter;
import com.progenie.shared.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts wrong passwords per account: 10 within 15 minutes lock password login for 15 minutes
 * (OTP login keeps working). Writes in its own transaction because the login call itself fails.
 */
@Component
class LoginGuard {

    private static final Logger log = LoggerFactory.getLogger(LoginGuard.class);

    private final RateLimiter rateLimiter;
    private final JdbcClient jdbc;
    private final AuthProperties props;
    private final Clock clock;

    LoginGuard(RateLimiter rateLimiter, JdbcClient jdbc, AuthProperties props, Clock clock) {
        this.rateLimiter = rateLimiter;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    /** Returns the lock expiry if this failure locked the account, otherwise null. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Instant recordFailure(UUID userId) {
        int failures = rateLimiter.hit(key(userId), props.loginFailureWindow());
        if (failures < props.loginMaxFailures()) {
            return null;
        }
        Instant until = clock.instant().plus(props.loginLockDuration());
        jdbc.sql("UPDATE users SET password_locked_until = :until WHERE id = :id")
            .param("until", Times.odt(until)).param("id", userId).update();
        rateLimiter.reset(key(userId));
        log.warn("Password login locked for user {} until {}", userId, until);
        return until;
    }

    public void clear(UUID userId) {
        rateLimiter.reset(key(userId));
    }

    private static String key(UUID userId) {
        return "login-fail:" + userId;
    }
}
