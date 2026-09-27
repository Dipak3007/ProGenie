package com.progenie.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.progenie.pricing.PriceCalculator.CityPricing;
import com.progenie.pricing.PriceCalculator.PriceBreakdown;
import org.junit.jupiter.api.Test;

class PriceCalculatorTest {

    /** Ahmedabad trial values: 5% commission, free up to 2 km, then ₹30 + ₹10/km, capped at ₹200. */
    private static final CityPricing AHMEDABAD = new CityPricing(
        new BigDecimal("0.05"), new BigDecimal("2"), new BigDecimal("30"), new BigDecimal("10"), new BigDecimal("200"));

    @Test
    void travelFeeIsFreeWithinTwoKm() {
        assertThat(PriceCalculator.travelFee(new BigDecimal("1.99"), AHMEDABAD)).isEqualByComparingTo("0.00");
        assertThat(PriceCalculator.travelFee(new BigDecimal("2.00"), AHMEDABAD)).isEqualByComparingTo("0.00");
    }

    @Test
    void travelFeeGrowsPerKmAfterFreeDistance() {
        // 30 + 10 × (6.4 − 2) = 74
        assertThat(PriceCalculator.travelFee(new BigDecimal("6.4"), AHMEDABAD)).isEqualByComparingTo("74.00");
    }

    @Test
    void travelFeeIsCapped() {
        assertThat(PriceCalculator.travelFee(new BigDecimal("40"), AHMEDABAD)).isEqualByComparingTo("200.00");
    }

    @Test
    void commissionIsOnlyOnServiceAmount() {
        // The worked example from the architecture doc
        PriceBreakdown q = PriceCalculator.quote(new BigDecimal("499"), new BigDecimal("6.4"), new BigDecimal("50"), AHMEDABAD);

        assertThat(q.serviceAmount()).isEqualByComparingTo("499.00");
        assertThat(q.travelFee()).isEqualByComparingTo("74.00");
        assertThat(q.totalAmount()).isEqualByComparingTo("623.00");
        assertThat(q.commissionAmount()).isEqualByComparingTo("24.95");
        assertThat(q.genieEarning()).isEqualByComparingTo("598.05");
    }

    @Test
    void missingTipCountsAsZero() {
        PriceBreakdown q = PriceCalculator.quote(new BigDecimal("249"), new BigDecimal("1"), null, AHMEDABAD);
        assertThat(q.tipAmount()).isEqualByComparingTo("0.00");
        assertThat(q.totalAmount()).isEqualByComparingTo("249.00");
    }
}
