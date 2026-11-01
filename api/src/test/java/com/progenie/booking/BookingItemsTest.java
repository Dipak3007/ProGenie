package com.progenie.booking;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookingItemsTest {

    @Test
    void theLabelNamesEveryServiceWithItsQuantity() {
        assertThat(BookingItems.label(List.of("Fan installation", "Switchboard repair"), List.of(2, 1)))
            .isEqualTo("Fan installation ×2 + Switchboard repair");
        assertThat(BookingItems.label(List.of("Tap repair"), List.of(1))).isEqualTo("Tap repair");
        String longName = "x".repeat(120);
        assertThat(BookingItems.label(List.of(longName, longName, longName, longName), List.of(5, 5, 5, 5))).hasSize(400).endsWith("…");
    }
}
