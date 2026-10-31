package com.progenie.booking;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BookingTrackingTest {

    @Test
    void arrivalEstimateIsRoundedUpAtCitySpeed() {
        assertThat(BookingTracking.etaMinutes(new BigDecimal("11.00"))).isEqualTo(30);
        assertThat(BookingTracking.etaMinutes(new BigDecimal("0.05"))).isEqualTo(1);
        assertThat(BookingTracking.etaMinutes(BigDecimal.ZERO)).isZero();
    }
}
