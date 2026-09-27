package com.progenie.booking;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import com.progenie.booking.domain.BookingStatus;

/** Pure booking rules (no Spring, no database), unit tested in BookingPolicyTest. */
public final class BookingPolicy {

    private BookingPolicy() {
    }

    /**
     * Customer cancellation fee: free while the Genie has not accepted yet, and free until
     * {@code freeWindow} before the slot; after that the fixed late fee applies.
     */
    public static BigDecimal customerCancellationFee(BookingStatus status, Instant slotStart, Instant now,
                                                     Duration freeWindow, BigDecimal lateFee) {
        if (status != BookingStatus.ACCEPTED) {
            return BigDecimal.ZERO;
        }
        return now.isBefore(slotStart.minus(freeWindow)) ? BigDecimal.ZERO : lateFee;
    }

    /** The Genie must answer within the TTL, and never later than the slot start. */
    public static Instant requestExpiry(Instant now, Instant slotStart, Duration ttl) {
        Instant byTtl = now.plus(ttl);
        return byTtl.isBefore(slotStart) ? byTtl : slotStart;
    }

    /** Customers can move a booking only while it is still free to cancel. */
    public static boolean canReschedule(BookingStatus status, Instant slotStart, Instant now, Duration freeWindow,
                                        int rescheduledCount, int maxReschedules) {
        return (status == BookingStatus.REQUESTED || status == BookingStatus.ACCEPTED)
            && now.isBefore(slotStart.minus(freeWindow))
            && rescheduledCount < maxReschedules;
    }

    /** A Genie may start a little before the slot, never earlier. */
    public static boolean canStart(Instant slotStart, Instant now, Duration earlyWindow) {
        return !now.isBefore(slotStart.minus(earlyWindow));
    }
}
