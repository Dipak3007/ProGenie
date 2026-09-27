package com.progenie.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Pure pricing rules (no Spring, no database) so they are easy to unit test.
 *
 * <ul>
 *   <li>Travel fee: free up to {@code freeKm}; then base + perKm × (distance − freeKm), capped.</li>
 *   <li>Commission: {@code commissionRate} × service amount only. Travel fee and tip go 100% to the Genie.</li>
 * </ul>
 * All money is BigDecimal rounded HALF_UP to paise.
 */
public final class PriceCalculator {

    private PriceCalculator() {
    }

    /** Per-city pricing parameters (table {@code city_pricing}). */
    public record CityPricing(BigDecimal commissionRate, BigDecimal travelFreeKm, BigDecimal travelBaseFee,
                              BigDecimal travelPerKm, BigDecimal travelFeeCap) {
    }

    public record PriceBreakdown(BigDecimal serviceAmount, BigDecimal distanceKm, BigDecimal travelFee,
                                 BigDecimal tipAmount, BigDecimal totalAmount, BigDecimal commissionRate,
                                 BigDecimal commissionAmount, BigDecimal genieEarning) {
    }

    public static BigDecimal travelFee(BigDecimal distanceKm, CityPricing p) {
        if (distanceKm.compareTo(p.travelFreeKm()) <= 0) {
            return money(BigDecimal.ZERO);
        }
        BigDecimal fee = p.travelBaseFee().add(p.travelPerKm().multiply(distanceKm.subtract(p.travelFreeKm())));
        return money(fee.min(p.travelFeeCap()));
    }

    public static PriceBreakdown quote(BigDecimal serviceAmount, BigDecimal distanceKm, BigDecimal tip, CityPricing p) {
        BigDecimal service = money(serviceAmount);
        BigDecimal distance = distanceKm.setScale(2, RoundingMode.HALF_UP);
        BigDecimal travel = travelFee(distance, p);
        BigDecimal tipAmount = money(tip == null ? BigDecimal.ZERO : tip);
        BigDecimal commission = money(service.multiply(p.commissionRate()));
        BigDecimal total = service.add(travel).add(tipAmount);
        BigDecimal genieEarning = total.subtract(commission);
        return new PriceBreakdown(service, distance, travel, tipAmount, total, p.commissionRate(), commission, genieEarning);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
