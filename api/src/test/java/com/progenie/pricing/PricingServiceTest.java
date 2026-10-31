package com.progenie.pricing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PricingServiceTest {

    @Test
    void straightLineDistanceIsTheGreatCircle() {
        // Navrangpura to Gota, Ahmedabad: about 8.6 km as the crow flies
        assertThat(PricingService.straightKm(23.0319, 72.5611, 23.1070, 72.5390).doubleValue()).isBetween(8.4, 8.8);
        assertThat(PricingService.straightKm(23.0, 72.5, 23.0, 72.5)).isEqualByComparingTo("0");
    }
}
