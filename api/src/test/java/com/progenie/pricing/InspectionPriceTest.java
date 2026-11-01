package com.progenie.pricing;

import java.math.BigDecimal;
import java.util.List;

import com.progenie.pricing.PriceCalculator.CityPricing;
import com.progenie.pricing.PriceCalculator.DistanceMethod;
import com.progenie.pricing.PriceCalculator.PriceBreakdown;
import com.progenie.pricing.PriceCalculator.ServicePrice;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3.5D: an inspection visit costs the city's fee plus travel, with no commission, and takes 30 minutes. */
class InspectionPriceTest {

    private static final CityPricing AHMEDABAD = new CityPricing(
        new BigDecimal("0.05"), new BigDecimal("2"), new BigDecimal("30"), new BigDecimal("10"), new BigDecimal("200"));

    @Test
    void theVisitIsTheFeePlusTravelAndTheGenieKeepsAllOfIt() {
        PriceBreakdown q = PriceCalculator.inspection(
            List.of(new ServicePrice(5, "House wiring check-up", new BigDecimal("659"), 90, true)), List.of(1),
            new BigDecimal("199"), new BigDecimal("6.4"), DistanceMethod.ESTIMATE, new BigDecimal("20"), AHMEDABAD);
        assertThat(q.inspection()).isTrue();
        assertThat(q.serviceAmount()).isEqualByComparingTo("199.00");
        assertThat(q.lines().getFirst().amount()).isEqualByComparingTo("0.00");
        assertThat(q.commissionAmount()).isEqualByComparingTo("0.00");
        assertThat(q.totalAmount()).isEqualByComparingTo("293.00");          // 199 + 74 travel + 20 tip
        assertThat(q.genieEarning()).isEqualByComparingTo(q.totalAmount());
        assertThat(q.durationMinutes()).isEqualTo(PriceCalculator.INSPECTION_MINUTES);
    }
}
