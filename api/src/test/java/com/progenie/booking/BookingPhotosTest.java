package com.progenie.booking;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.progenie.booking.BookingPhotos.Stage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookingPhotosTest {

    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");

    @Test
    void beforePhotosOnlyWhileTheJobIsInProgress() {
        assertThat(BookingPhotos.canChange(Stage.BEFORE, "IN_PROGRESS", null, NOW)).isTrue();
        assertThat(BookingPhotos.canChange(Stage.BEFORE, "ACCEPTED", null, NOW)).isFalse();
        assertThat(BookingPhotos.canChange(Stage.BEFORE, "COMPLETED", at(NOW.minusSeconds(60)), NOW)).isFalse();
    }

    @Test
    void afterPhotosUntil24HoursAfterCompletion() {
        assertThat(BookingPhotos.canChange(Stage.AFTER, "IN_PROGRESS", null, NOW)).isTrue();
        assertThat(BookingPhotos.canChange(Stage.AFTER, "COMPLETED", at(NOW.minusSeconds(23 * 3600)), NOW)).isTrue();
        assertThat(BookingPhotos.canChange(Stage.AFTER, "COMPLETED", at(NOW.minusSeconds(25 * 3600)), NOW)).isFalse();
        assertThat(BookingPhotos.canChange(Stage.AFTER, "CANCELLED", null, NOW)).isFalse();
    }

    private static OffsetDateTime at(Instant i) {
        return i.atOffset(ZoneOffset.UTC);
    }
}
