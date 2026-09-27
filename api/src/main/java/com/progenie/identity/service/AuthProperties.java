package com.progenie.identity.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code progenie.auth.*}: one-time codes, password reset and login lockout (design doc 16.2).
 *
 * @param otpPepper              HMAC key for codes; must be a long random secret in production
 * @param requireVerifiedPhone   an unverified phone can browse but not book (or, for a Genie, submit for review)
 */
@ConfigurationProperties(prefix = "progenie.auth")
public record AuthProperties(
    @DefaultValue("local-dev-otp-pepper-change-me") String otpPepper,
    @DefaultValue("5m") Duration otpTtl,
    @DefaultValue("5") int otpMaxAttempts,
    @DefaultValue("30s") Duration otpResendAfter,
    @DefaultValue("5") int otpPerDestinationPerHour,
    @DefaultValue("20") int otpPerIpPerHour,
    @DefaultValue("15m") Duration resetTokenTtl,
    @DefaultValue("10") int loginMaxFailures,
    @DefaultValue("15m") Duration loginFailureWindow,
    @DefaultValue("15m") Duration loginLockDuration,
    @DefaultValue("true") boolean requireVerifiedPhone) {
}
