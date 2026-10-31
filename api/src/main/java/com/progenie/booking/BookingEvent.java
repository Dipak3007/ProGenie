package com.progenie.booking;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after every booking state change. Listeners: notification (after commit) and payment
 * (COMPLETED with cash collected → ledger, in the same transaction).
 *
 * @param actorRole     CUSTOMER, GENIE, ADMIN or SYSTEM
 * @param cashCollected only meaningful for COMPLETED
 */
public record BookingEvent(Type type, UUID bookingId, String bookingRef, UUID customerId, UUID genieId,
                           Instant slotStart, String actorRole, String reason, boolean cashCollected) {

    /** {@code reason} of the ACCEPTED event of a Genie Now booking (Phase 3C-9): the Genie is coming right away. */
    public static final String GENIE_NOW = "Genie Now";

    public enum Type { REQUESTED, ACCEPTED, DECLINED, EXPIRED, CANCELLED, RESCHEDULED, ON_THE_WAY, STARTED, COMPLETED }
}
