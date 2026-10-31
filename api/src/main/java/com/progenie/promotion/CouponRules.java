package com.progenie.promotion;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

/**
 * Pure coupon rules (no Spring, no database), unit tested.
 *
 * <ul>
 *   <li>The discount is on the service amount only: PERCENT = service × value % (capped by {@code maxDiscount}),
 *       FLAT = value; never more than the service amount. Rounded to paise.</li>
 *   <li>A coupon applies when it is ACTIVE, inside its dates, matches the scope (all, a category, a service or a
 *       city), the service amount reaches {@code minOrder}, it has uses left in total and for this customer, and,
 *       when it is for first bookings only, the customer has no other booking that went ahead.</li>
 * </ul>
 */
public final class CouponRules {

    private CouponRules() {
    }

    /** A coupon as stored (table {@code coupons}). */
    public record Coupon(long id, String code, String title, @Nullable String description, String discountType,
                         BigDecimal discountValue, @Nullable BigDecimal maxDiscount, BigDecimal minOrder,
                         OffsetDateTime validFrom, @Nullable OffsetDateTime validUntil, @Nullable Integer totalLimit,
                         int perCustomerLimit, boolean firstBookingOnly, String scope, @Nullable Long categoryId,
                         @Nullable Long serviceId, @Nullable Long cityId, boolean isPublic, String status) {
    }

    /** What is being booked. */
    public record Order(long serviceId, long categoryId, long cityId, BigDecimal serviceAmount) {
    }

    /** How often the coupon was used, overall and by this customer, and whether the customer booked before. */
    public record Usage(long totalUses, long customerUses, boolean customerHasBooked) {
    }

    /** Why a coupon does not apply: {@code code} for the app, {@code message} for people. */
    public record Refusal(String code, String message) {
    }

    public static BigDecimal discount(Coupon c, BigDecimal serviceAmount) {
        BigDecimal amount = "PERCENT".equals(c.discountType())
            ? serviceAmount.multiply(c.discountValue()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
            : c.discountValue();
        if (c.maxDiscount() != null) {
            amount = amount.min(c.maxDiscount());
        }
        return amount.min(serviceAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /** {@code null} when the coupon applies, else the first reason it doesn't. */
    public static @Nullable Refusal check(Coupon c, Order order, Usage usage, Instant now) {
        if (!"ACTIVE".equals(c.status())) {
            return new Refusal("COUPON_PAUSED", "This coupon is not available right now");
        }
        if (now.isBefore(c.validFrom().toInstant())) {
            return new Refusal("COUPON_NOT_STARTED", "This coupon can't be used yet");
        }
        if (c.validUntil() != null && !now.isBefore(c.validUntil().toInstant())) {
            return new Refusal("COUPON_EXPIRED", "This coupon has expired");
        }
        if (!inScope(c, order)) {
            return new Refusal("COUPON_NOT_APPLICABLE", "This coupon doesn't apply to this "
                + ("CITY".equals(c.scope()) ? "city" : "service"));
        }
        if (order.serviceAmount().compareTo(c.minOrder()) < 0) {
            return new Refusal("COUPON_MIN_ORDER", "This coupon needs a service of at least ₹" + plain(c.minOrder()));
        }
        if (c.totalLimit() != null && usage.totalUses() >= c.totalLimit()) {
            return new Refusal("COUPON_USED_UP", "This coupon has been fully used");
        }
        if (usage.customerUses() >= c.perCustomerLimit()) {
            return new Refusal("COUPON_ALREADY_USED", c.perCustomerLimit() == 1
                ? "You have already used this coupon" : "You have used this coupon " + c.perCustomerLimit() + " times");
        }
        if (c.firstBookingOnly() && usage.customerHasBooked()) {
            return new Refusal("COUPON_FIRST_BOOKING_ONLY", "This coupon is for your first booking only");
        }
        return null;
    }

    /** Whether the coupon covers this order's category, service or city (always, for scope ALL). */
    public static boolean inScope(Coupon c, Order order) {
        return switch (c.scope()) {
            case "CATEGORY" -> c.categoryId() != null && c.categoryId() == order.categoryId();
            case "SERVICE" -> c.serviceId() != null && c.serviceId() == order.serviceId();
            case "CITY" -> c.cityId() != null && c.cityId() == order.cityId();
            default -> true;
        };
    }

    /** Codes are case-insensitive: stored and compared in upper case without spaces. */
    public static String normalise(@Nullable String code) {
        return code == null ? "" : code.trim().replace(" ", "").toUpperCase(Locale.ROOT);
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().scale() <= 0 ? v.setScale(0, RoundingMode.HALF_UP).toPlainString() : v.toPlainString();
    }
}
