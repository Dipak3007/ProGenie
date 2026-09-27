package com.progenie.booking.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * REQUESTED → ACCEPTED → IN_PROGRESS → COMPLETED, with REJECTED / EXPIRED / CANCELLED as exits.
 * "Active" bookings hold the Genie's time slot (the DB exclusion constraint uses the same set).
 */
public enum BookingStatus {
    REQUESTED, ACCEPTED, IN_PROGRESS, COMPLETED, REJECTED, EXPIRED, CANCELLED;

    public static final Set<BookingStatus> ACTIVE = EnumSet.of(REQUESTED, ACCEPTED, IN_PROGRESS);

    public boolean isActive() {
        return ACTIVE.contains(this);
    }
}
