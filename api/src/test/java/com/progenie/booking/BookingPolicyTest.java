package com.progenie.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import com.progenie.booking.domain.BookingStatus;
import org.junit.jupiter.api.Test;

class BookingPolicyTest {

    private static final Instant SLOT = Instant.parse("2026-10-05T06:00:00Z");
    private static final Duration FREE_WINDOW = Duration.ofHours(2);
    private static final BigDecimal FEE = new BigDecimal("49.00");

    @Test
    void cancellingARequestThatWasNotAcceptedIsAlwaysFree() {
        Instant tenMinutesBefore = SLOT.minus(Duration.ofMinutes(10));
        assertThat(BookingPolicy.customerCancellationFee(BookingStatus.REQUESTED, SLOT, tenMinutesBefore, FREE_WINDOW, FEE))
            .isEqualByComparingTo("0");
    }

    @Test
    void acceptedBookingIsFreeToCancelUntilTwoHoursBefore() {
        Instant threeHoursBefore = SLOT.minus(Duration.ofHours(3));
        Instant justInside = SLOT.minus(FREE_WINDOW).minusSeconds(1);
        assertThat(BookingPolicy.customerCancellationFee(BookingStatus.ACCEPTED, SLOT, threeHoursBefore, FREE_WINDOW, FEE))
            .isEqualByComparingTo("0");
        assertThat(BookingPolicy.customerCancellationFee(BookingStatus.ACCEPTED, SLOT, justInside, FREE_WINDOW, FEE))
            .isEqualByComparingTo("0");
    }

    @Test
    void acceptedBookingCancelledLateOwesTheFee() {
        Instant exactlyTwoHoursBefore = SLOT.minus(FREE_WINDOW);
        Instant oneHourBefore = SLOT.minus(Duration.ofHours(1));
        assertThat(BookingPolicy.customerCancellationFee(BookingStatus.ACCEPTED, SLOT, exactlyTwoHoursBefore, FREE_WINDOW, FEE))
            .isEqualByComparingTo("49.00");
        assertThat(BookingPolicy.customerCancellationFee(BookingStatus.ACCEPTED, SLOT, oneHourBefore, FREE_WINDOW, FEE))
            .isEqualByComparingTo("49.00");
    }

    @Test
    void requestExpiresAfterTheTtlButNeverAfterTheSlotStarts() {
        Duration ttl = Duration.ofMinutes(30);
        Instant earlyBooking = SLOT.minus(Duration.ofHours(10));
        assertThat(BookingPolicy.requestExpiry(earlyBooking, SLOT, ttl)).isEqualTo(earlyBooking.plus(ttl));
        Instant lastMinute = SLOT.minus(Duration.ofMinutes(10));
        assertThat(BookingPolicy.requestExpiry(lastMinute, SLOT, ttl)).isEqualTo(SLOT);
    }

    @Test
    void rescheduleNeedsAnActiveBookingInsideTheFreeWindowAndUnderTheLimit() {
        Instant early = SLOT.minus(Duration.ofHours(5));
        assertThat(BookingPolicy.canReschedule(BookingStatus.ACCEPTED, SLOT, early, FREE_WINDOW, 0, 2)).isTrue();
        assertThat(BookingPolicy.canReschedule(BookingStatus.REQUESTED, SLOT, early, FREE_WINDOW, 1, 2)).isTrue();
        assertThat(BookingPolicy.canReschedule(BookingStatus.ACCEPTED, SLOT, early, FREE_WINDOW, 2, 2)).isFalse();
        assertThat(BookingPolicy.canReschedule(BookingStatus.IN_PROGRESS, SLOT, early, FREE_WINDOW, 0, 2)).isFalse();
        assertThat(BookingPolicy.canReschedule(BookingStatus.ACCEPTED, SLOT, SLOT.minus(Duration.ofHours(1)), FREE_WINDOW, 0, 2)).isFalse();
    }

    @Test
    void genieCanStartAtMostThirtyMinutesEarly() {
        Duration early = Duration.ofMinutes(30);
        assertThat(BookingPolicy.canStart(SLOT, SLOT.minus(Duration.ofMinutes(31)), early)).isFalse();
        assertThat(BookingPolicy.canStart(SLOT, SLOT.minus(Duration.ofMinutes(30)), early)).isTrue();
        assertThat(BookingPolicy.canStart(SLOT, SLOT.plus(Duration.ofMinutes(15)), early)).isTrue();
    }
}
