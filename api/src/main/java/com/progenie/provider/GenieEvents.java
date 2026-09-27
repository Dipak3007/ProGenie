package com.progenie.provider;

import java.util.UUID;

/** Events published by the provider module (the notification module reacts to them). */
public final class GenieEvents {

    private GenieEvents() {
    }

    /** A Genie submitted their profile and KYC for review. */
    public record GenieSubmitted(UUID genieId, String fullName) {
    }

    /** An admin decided on a Genie (approved, asked for changes, rejected, suspended, reinstated). */
    public record GenieVerificationDecided(UUID genieId, String fromStatus, String toStatus, String note) {
    }

    /** A Genie crossed the cancellation threshold and needs an admin's attention. */
    public record GenieFlagged(UUID genieId, String fullName, String reason) {
    }
}
