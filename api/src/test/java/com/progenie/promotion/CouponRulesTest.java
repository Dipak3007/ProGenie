package com.progenie.promotion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.progenie.promotion.CouponRules.Coupon;
import com.progenie.promotion.CouponRules.Order;
import com.progenie.promotion.CouponRules.Usage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CouponRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final OffsetDateTime YESTERDAY = NOW.minusSeconds(86_400).atOffset(ZoneOffset.UTC);
    private static final Order FAN = new Order(1, 1, 1, new BigDecimal("349.00"));
    private static final Usage FRESH = new Usage(0, 0, false);

    private static Coupon percent(String value, String cap) {
        return coupon("PERCENT", value, cap, "0", null, 1, false, "ALL", null, null, null, "ACTIVE", null);
    }

    private static Coupon coupon(String type, String value, String cap, String minOrder, Integer totalLimit, int perCustomer,
                                 boolean firstOnly, String scope, Long category, Long service, Long city, String status,
                                 OffsetDateTime until) {
        return new Coupon(1, "TEST", "Test", null, type, new BigDecimal(value), cap == null ? null : new BigDecimal(cap),
            new BigDecimal(minOrder), YESTERDAY, until, totalLimit, perCustomer, firstOnly, scope, category, service, city,
            true, status);
    }

    @Test
    void percentageIsOnTheServiceAmountAndCapped() {
        assertThat(CouponRules.discount(percent("10", null), new BigDecimal("349.00"))).isEqualByComparingTo("34.90");
        assertThat(CouponRules.discount(percent("10", "30"), new BigDecimal("349.00"))).isEqualByComparingTo("30.00");
        assertThat(CouponRules.discount(percent("12.5", null), new BigDecimal("219.00"))).isEqualByComparingTo("27.38");
    }

    @Test
    void aFlatDiscountNeverExceedsTheServiceAmount() {
        Coupon flat = coupon("FLAT", "500", null, "0", null, 1, false, "ALL", null, null, null, "ACTIVE", null);
        assertThat(CouponRules.discount(flat, new BigDecimal("349.00"))).isEqualByComparingTo("349.00");
    }

    @Test
    void appliesOnlyWhenActiveAndInsideItsDates() {
        assertThat(CouponRules.check(percent("10", null), FAN, FRESH, NOW)).isNull();
        Coupon paused = coupon("FLAT", "50", null, "0", null, 1, false, "ALL", null, null, null, "PAUSED", null);
        assertThat(CouponRules.check(paused, FAN, FRESH, NOW).code()).isEqualTo("COUPON_PAUSED");
        Coupon expired = coupon("FLAT", "50", null, "0", null, 1, false, "ALL", null, null, null, "ACTIVE", NOW.atOffset(ZoneOffset.UTC));
        assertThat(CouponRules.check(expired, FAN, FRESH, NOW).code()).isEqualTo("COUPON_EXPIRED");
        assertThat(CouponRules.check(percent("10", null), FAN, FRESH, YESTERDAY.toInstant().minusSeconds(1)).code())
            .isEqualTo("COUPON_NOT_STARTED");
    }

    @Test
    void scopeMinimumOrderAndLimits() {
        Coupon plumbing = coupon("FLAT", "50", null, "0", null, 1, false, "CATEGORY", 2L, null, null, "ACTIVE", null);
        assertThat(CouponRules.check(plumbing, FAN, FRESH, NOW).code()).isEqualTo("COUPON_NOT_APPLICABLE");
        Coupon otherCity = coupon("FLAT", "50", null, "0", null, 1, false, "CITY", null, null, 2L, "ACTIVE", null);
        assertThat(CouponRules.check(otherCity, FAN, FRESH, NOW).message()).contains("city");
        Coupon thisService = coupon("FLAT", "50", null, "0", null, 1, false, "SERVICE", null, 1L, null, "ACTIVE", null);
        assertThat(CouponRules.check(thisService, FAN, FRESH, NOW)).isNull();

        Coupon big = coupon("FLAT", "50", null, "399", null, 1, false, "ALL", null, null, null, "ACTIVE", null);
        assertThat(CouponRules.check(big, FAN, FRESH, NOW).message()).isEqualTo("This coupon needs a service of at least ₹399");

        Coupon limited = coupon("FLAT", "50", null, "0", 100, 2, false, "ALL", null, null, null, "ACTIVE", null);
        assertThat(CouponRules.check(limited, FAN, new Usage(100, 0, false), NOW).code()).isEqualTo("COUPON_USED_UP");
        assertThat(CouponRules.check(limited, FAN, new Usage(5, 2, false), NOW).code()).isEqualTo("COUPON_ALREADY_USED");
        assertThat(CouponRules.check(limited, FAN, new Usage(5, 1, false), NOW)).isNull();

        Coupon welcome = coupon("FLAT", "50", null, "0", null, 1, true, "ALL", null, null, null, "ACTIVE", null);
        assertThat(CouponRules.check(welcome, FAN, new Usage(0, 0, true), NOW).code()).isEqualTo("COUPON_FIRST_BOOKING_ONLY");
    }

    @Test
    void codesAreCaseAndSpaceInsensitive() {
        assertThat(CouponRules.normalise(" welcome 50 ")).isEqualTo("WELCOME50");
        assertThat(CouponRules.normalise(null)).isEmpty();
    }
}
