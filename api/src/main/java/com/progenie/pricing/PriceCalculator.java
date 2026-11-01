package com.progenie.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure pricing rules (no Spring, no database) so they are easy to unit test.
 *
 * <ul>
 *   <li>Travel fee: free up to {@code freeKm}; then base + perKm × (distance − freeKm), capped.</li>
 *   <li>Commission: {@code commissionRate} × service amount only, per line (Phase 3.5C: several services in one
 *   booking). Travel fee and tip go 100% to the Genie; one travel fee per booking.</li>
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

    /** How the travel distance was measured: ROAD (routing engine) or ESTIMATE (straight line × road factor). */
    public enum DistanceMethod {
        ROAD,
        ESTIMATE
    }

    /**
     * One service in a quote: {@code amount} = unit price × quantity; {@code durationMinutes} is per unit (the line
     * takes quantity × that).
     */
    public record PriceLine(long serviceId, String serviceName, int quantity, BigDecimal unitPrice, BigDecimal amount,
                            int durationMinutes, BigDecimal commissionAmount) {
    }

    /**
     * A service's price and duration for a Genie, before quantities. {@code inspectionFirst} (Phase 3.5D): booked as
     * an inspection visit first.
     */
    public record ServicePrice(long serviceId, String serviceName, BigDecimal unitPrice, int durationMinutes,
                               boolean inspectionFirst) {
    }

    /** How long an inspection visit takes (Phase 3.5D). */
    public static final int INSPECTION_MINUTES = 30;

    /**
     * @param lines           the services, in the order they were chosen (Phase 3.5C)
     * @param durationMinutes how long the job takes in total (the slot length)
     * @param inspection      Phase 3.5D: one of the services needs an inspection visit first. Then {@code lines} are
     *                        the services to look at (₹0), {@code serviceAmount} is the inspection fee (no commission)
     *                        and the visit takes {@value #INSPECTION_MINUTES} minutes
     */
    public record PriceBreakdown(BigDecimal serviceAmount, BigDecimal distanceKm, DistanceMethod distanceMethod,
                                 BigDecimal travelFee, BigDecimal tipAmount, BigDecimal totalAmount,
                                 BigDecimal commissionRate, BigDecimal commissionAmount, BigDecimal genieEarning,
                                 List<PriceLine> lines, int durationMinutes, boolean inspection, BigDecimal inspectionFee) {
    }

    public static BigDecimal travelFee(BigDecimal distanceKm, CityPricing p) {
        if (distanceKm.compareTo(p.travelFreeKm()) <= 0) {
            return money(BigDecimal.ZERO);
        }
        BigDecimal fee = p.travelBaseFee().add(p.travelPerKm().multiply(distanceKm.subtract(p.travelFreeKm())));
        return money(fee.min(p.travelFeeCap()));
    }

    /** A single service, once (quantity 1, no duration). */
    public static PriceBreakdown quote(BigDecimal serviceAmount, BigDecimal distanceKm, DistanceMethod method,
                                       BigDecimal tip, CityPricing p) {
        return quote(List.of(new ServicePrice(0, "", serviceAmount, 0, false)), List.of(1), distanceKm, method, tip, p);
    }

    /**
     * Several services (Phase 3.5C): {@code quantities.get(i)} of {@code services.get(i)}. Each line pays commission
     * on its own amount; the booking's service amount and commission are the sums.
     */
    public static PriceBreakdown quote(List<ServicePrice> services, List<Integer> quantities, BigDecimal distanceKm,
                                       DistanceMethod method, BigDecimal tip, CityPricing p) {
        List<PriceLine> lines = new ArrayList<>();
        BigDecimal service = money(BigDecimal.ZERO);
        BigDecimal commission = money(BigDecimal.ZERO);
        int minutes = 0;
        for (int i = 0; i < services.size(); i++) {
            ServicePrice sp = services.get(i);
            int quantity = quantities.get(i);
            BigDecimal amount = money(sp.unitPrice().multiply(BigDecimal.valueOf(quantity)));
            BigDecimal lineCommission = money(amount.multiply(p.commissionRate()));
            lines.add(new PriceLine(sp.serviceId(), sp.serviceName(), quantity, money(sp.unitPrice()), amount,
                sp.durationMinutes(), lineCommission));
            service = service.add(amount);
            commission = commission.add(lineCommission);
            minutes += sp.durationMinutes() * quantity;
        }
        BigDecimal distance = distanceKm.setScale(2, RoundingMode.HALF_UP);
        BigDecimal travel = travelFee(distance, p);
        BigDecimal tipAmount = money(tip == null ? BigDecimal.ZERO : tip);
        BigDecimal total = service.add(travel).add(tipAmount);
        BigDecimal genieEarning = total.subtract(commission);
        return new PriceBreakdown(service, distance, method, travel, tipAmount, total, p.commissionRate(), commission,
            genieEarning, List.copyOf(lines), minutes, false, money(BigDecimal.ZERO));
    }

    /**
     * An inspection visit (Phase 3.5D) for services that need one: the services are listed at ₹0, the customer pays
     * the city's inspection fee and the travel fee; the Genie keeps both (no commission).
     */
    public static PriceBreakdown inspection(List<ServicePrice> services, List<Integer> quantities, BigDecimal fee,
                                            BigDecimal distanceKm, DistanceMethod method, BigDecimal tip, CityPricing p) {
        List<PriceLine> lines = new ArrayList<>();
        BigDecimal zero = money(BigDecimal.ZERO);
        for (int i = 0; i < services.size(); i++) {
            ServicePrice sp = services.get(i);
            lines.add(new PriceLine(sp.serviceId(), sp.serviceName(), quantities.get(i), zero, zero, sp.durationMinutes(), zero));
        }
        BigDecimal inspectionFee = money(fee);
        BigDecimal distance = distanceKm.setScale(2, RoundingMode.HALF_UP);
        BigDecimal travel = travelFee(distance, p);
        BigDecimal tipAmount = money(tip == null ? BigDecimal.ZERO : tip);
        BigDecimal total = inspectionFee.add(travel).add(tipAmount);
        return new PriceBreakdown(inspectionFee, distance, method, travel, tipAmount, total, p.commissionRate(), zero,
            total, List.copyOf(lines), INSPECTION_MINUTES, true, inspectionFee);
    }

    /**
     * Splits a coupon discount over the lines it covers ({@code eligible}), in proportion to their amounts; the last
     * covered line takes the rounding remainder. Lines it doesn't cover get 0.
     */
    public static List<BigDecimal> spread(BigDecimal discount, List<BigDecimal> amounts, List<Boolean> eligible) {
        BigDecimal base = BigDecimal.ZERO;
        int last = -1;
        for (int i = 0; i < amounts.size(); i++) {
            if (eligible.get(i)) {
                base = base.add(amounts.get(i));
                last = i;
            }
        }
        List<BigDecimal> shares = new ArrayList<>();
        BigDecimal left = money(discount);
        for (int i = 0; i < amounts.size(); i++) {
            if (!eligible.get(i) || base.signum() == 0) {
                shares.add(money(BigDecimal.ZERO));
            } else if (i == last) {
                shares.add(left.min(amounts.get(i)).max(BigDecimal.ZERO));
            } else {
                BigDecimal share = money(discount.multiply(amounts.get(i)).divide(base, 2, RoundingMode.HALF_UP)).min(left);
                shares.add(share);
                left = left.subtract(share);
            }
        }
        return shares;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
