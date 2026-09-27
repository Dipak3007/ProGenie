package com.progenie.identity;

import java.util.UUID;

/**
 * A one-time code was issued. The plain code exists only in this in-memory event; the database keeps
 * an HMAC of it. The notification module turns it into an SMS (phone) or email (email address).
 *
 * @param channel     "SMS" or "EMAIL"
 * @param purpose     LOGIN, RESET_PASSWORD, VERIFY_PHONE or VERIFY_EMAIL
 * @param ttlMinutes  how long the code stays valid
 */
public record OtpRequested(UUID challengeId, UUID userId, String fullName, String channel, String destination,
                           String purpose, String code, long ttlMinutes) {
}
