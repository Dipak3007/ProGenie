package com.progenie.pricing;

import java.math.BigDecimal;
import java.util.List;

import com.progenie.pricing.PriceCalculator.CityPricing;
import com.progenie.pricing.PriceCalculator.DistanceMethod;
import com.progenie.pricing.PriceCalculator.PriceBreakdown;
import com.progenie.pricing.PriceCalculator.ServicePrice;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3.5C: several services in one booking, priced per line with one travel fee. */
class SeveralServicesTest {

    private static final CityPricing AHMEDABAD = new CityPricing(
        new BigDecimal("0.05"), new BigDecimal("2"), new BigDecimal("30"), new BigDecimal("10"), new BigDecimal("200"));

    @Test
    void eachLineIsPriceTimesQuantityWithItsOwnCommissionAndOneTravelFee() {
        PriceBreakdown q = PriceCalculator.quote(
            List.of(new ServicePrice(1, "Fan installation", new BigDecimal("349"), 45, false),
                new ServicePrice(2, "Switchboard repair", new BigDecimal("219.50"), 30, false)),
            List.of(2, 1), new BigDecimal("6.4"), DistanceMethod.ESTIMATE, new BigDecimal("20"), AHMEDABAD);
        assertThat(q.lines()).hasSize(2);
        assertThat(q.lines().get(0).amount()).isEqualByComparingTo("698.00");
        assertThat(q.lines().get(0).commissionAmount()).isEqualByComparingTo("34.90");
        assertThat(q.lines().get(1).commissionAmount()).isEqualByComparingTo("10.98");   // 10.975 rounds up
        assertThat(q.serviceAmount()).isEqualByComparingTo("917.50");
        assertThat(q.commissionAmount()).isEqualByComparingTo("45.88");
        assertThat(q.travelFee()).isEqualByComparingTo("74.00");                          // once, not per service
        assertThat(q.totalAmount()).isEqualByComparingTo("1011.50");
        assertThat(q.genieEarning()).isEqualByComparingTo("965.62");
        assertThat(q.durationMinutes()).isEqualTo(2 * 45 + 30);
    }

    @Test
    void aCouponDiscountIsSpreadOverTheLinesItCoversOnly() {
        List<BigDecimal> shares = PriceCalculator.spread(new BigDecimal("100"),
            List.of(new BigDecimal("698"), new BigDecimal("219.50"), new BigDecimal("300")), List.of(true, false, true));
        assertThat(shares.get(0)).isEqualByComparingTo("69.94");
        assertThat(shares.get(1)).isEqualByComparingTo("0.00");
        assertThat(shares.get(2)).isEqualByComparingTo("30.06");                         // the remainder
        assertThat(shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
        assertThat(PriceCalculator.spread(new BigDecimal("50"), List.of(new BigDecimal("40")), List.of(true)).getFirst())
            .isEqualByComparingTo("40.00");                                               // never more than the line
    }
}
