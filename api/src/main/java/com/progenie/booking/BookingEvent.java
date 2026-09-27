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

    public enum Type { REQUESTED, ACCEPTED, DECLINED, EXPIRED, CANCELLED, RESCHEDULED, STARTED, COMPLETED }
}
