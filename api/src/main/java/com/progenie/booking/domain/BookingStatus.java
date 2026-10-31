package com.progenie.booking.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * REQUESTED → ACCEPTED → (ON_THE_WAY) → IN_PROGRESS → COMPLETED, with REJECTED / EXPIRED / CANCELLED as exits.
 * ON_THE_WAY (Phase 3C) is optional: the Genie can also start straight from ACCEPTED.
 * "Active" bookings hold the Genie's time slot (the DB exclusion constraint uses the same set).
 */
public enum BookingStatus {
    REQUESTED, ACCEPTED, ON_THE_WAY, IN_PROGRESS, COMPLETED, REJECTED, EXPIRED, CANCELLED;

    public static final Set<BookingStatus> ACTIVE = EnumSet.of(REQUESTED, ACCEPTED, ON_THE_WAY, IN_PROGRESS);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }

    /** Accepted and not started yet: the customer has a confirmed Genie (who may be travelling). */
    public boolean isConfirmed() {
        return this == ACCEPTED || this == ON_THE_WAY;
    }
}
