package com.progenie.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import com.progenie.booking.SlotCalculator.Slot;
import com.progenie.provider.GenieDirectory.Interval;
import com.progenie.provider.GenieDirectory.Window;
import org.junit.jupiter.api.Test;

class SlotCalculatorTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final List<Window> WEEKDAYS_9_TO_12 = List.of(new Window(1, LocalTime.of(9, 0), LocalTime.of(12, 0)));

    private static Instant ist(int hour, int minute) {
        return ZonedDateTime.of(MONDAY, LocalTime.of(hour, minute), IST).toInstant();
    }

    @Test
    void slotsFitInsideTheWindowOnTheStep() {
        List<Slot> slots = SlotCalculator.daySlots(MONDAY, IST, WEEKDAYS_9_TO_12, List.of(), Instant.EPOCH, 30, 60);
        // 09:00, 09:30, 10:00, 10:30, 11:00 (11:30 would end after 12:00)
        assertThat(slots).extracting(Slot::start)
            .containsExactly(ist(9, 0), ist(9, 30), ist(10, 0), ist(10, 30), ist(11, 0));
        assertThat(slots.getFirst().end()).isEqualTo(ist(10, 0));
    }

    @Test
    void noSlotsOnDaysWithoutAvailability() {
        assertThat(SlotCalculator.daySlots(MONDAY.plusDays(1), IST, WEEKDAYS_9_TO_12, List.of(), Instant.EPOCH, 30, 60)).isEmpty();
    }

    @Test
    void busyIntervalsAndTheEarliestTimeRemoveSlots() {
        List<Interval> busy = List.of(new Interval(ist(10, 0), ist(10, 45)));
        List<Slot> slots = SlotCalculator.daySlots(MONDAY, IST, WEEKDAYS_9_TO_12, busy, ist(9, 15), 30, 60);
        // 09:00 is before "earliest"; 09:30-10:30 and 10:00/10:30 overlap the busy block; 11:00 is free
        assertThat(slots).extracting(Slot::start).containsExactly(ist(11, 0));
    }

    @Test
    void touchingIntervalsDoNotOverlap() {
        List<Interval> busy = List.of(new Interval(ist(9, 0), ist(10, 0)));
        assertThat(SlotCalculator.overlaps(ist(10, 0), ist(11, 0), busy)).isFalse();
        assertThat(SlotCalculator.overlaps(ist(9, 59), ist(11, 0), busy)).isTrue();
    }

    @Test
    void fitsAvailabilityChecksWindowEndAndStepAlignment() {
        assertThat(SlotCalculator.fitsAvailability(ist(11, 0), 60, IST, WEEKDAYS_9_TO_12, 30)).isTrue();
        assertThat(SlotCalculator.fitsAvailability(ist(11, 30), 60, IST, WEEKDAYS_9_TO_12, 30)).isFalse();
        assertThat(SlotCalculator.fitsAvailability(ist(9, 10), 60, IST, WEEKDAYS_9_TO_12, 30)).isFalse();
        assertThat(SlotCalculator.fitsAvailability(ist(8, 30), 60, IST, WEEKDAYS_9_TO_12, 30)).isFalse();
    }
}
