package com.progenie.promotion;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.progenie.promotion.CouponRules.Coupon;
import com.progenie.promotion.CouponRules.Order;
import com.progenie.promotion.CouponRules.Refusal;
import com.progenie.promotion.CouponRules.Usage;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.Times;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Coupons for customers (checkout) and for the booking module (applying and giving back a coupon).
 * See {@link CouponRules} for what makes a coupon apply.
 */
@Service
public class CouponService {

    private static final Logger log = LoggerFactory.getLogger(CouponService.class);

    /**
     * A coupon as the checkout shows it, for one order: {@code discount} is what it takes off this service;
     * {@code applicable} false comes with the {@code reasonCode}/{@code reason} it can't be used.
     */
    public record CouponOfferDto(String code, String title, @Nullable String description, String discountType,
                                 BigDecimal discountValue, @Nullable BigDecimal maxDiscount, BigDecimal minOrder,
                                 @Nullable OffsetDateTime validUntil, boolean firstBookingOnly, BigDecimal discount,
                                 boolean applicable, @Nullable String reasonCode, @Nullable String reason) {
    }

    /** A coupon applied to a new booking: store {@code couponId} and {@code discount} on it, then call {@link #recordUse}. */
    public record AppliedCoupon(long couponId, String code, BigDecimal discount) {
    }

    record OrderRow(BigDecimal serviceAmount, long serviceId, long categoryId, long cityId) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public CouponService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Public coupons for this checkout (Genie, service, address), each with its discount or why it doesn't apply. */
    @Transactional(readOnly = true)
    public List<CouponOfferDto> available(UUID customerId, UUID genieId, long serviceId, UUID addressId) {
        Order order = order(customerId, genieId, serviceId, addressId);
        Instant now = clock.instant();
        boolean booked = customerHasBooked(customerId);
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_COUPON_PUBLIC_ACTIVE)).param("now", Times.odt(now))
            .query(Coupon.class).list().stream()
            .filter(c -> CouponRules.inScope(c, order))
            .map(c -> offer(c, order, usage(c, customerId, booked), now))
            .toList();
    }

    /** A typed code (public or private) for this checkout. 404 COUPON_NOT_FOUND / 422 with the reason. */
    @Transactional(readOnly = true)
    public CouponOfferDto check(UUID customerId, String code, UUID genieId, long serviceId, UUID addressId) {
        Order order = order(customerId, genieId, serviceId, addressId);
        Coupon c = find(code, false);
        CouponOfferDto offer = offer(c, order, usage(c, customerId, customerHasBooked(customerId)), clock.instant());
        if (!offer.applicable()) {
            throw ApiException.unprocessable(offer.reasonCode(), offer.reason());
        }
        return offer;
    }

    /**
     * Applies a coupon to a booking being created (inside its transaction). The coupon row is locked, so total and
     * per-customer limits hold even when two bookings race. Throws 404/422 when it doesn't apply.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AppliedCoupon apply(UUID customerId, String code, long serviceId, long cityId, BigDecimal serviceAmount) {
        Coupon c = find(code, true);
        long categoryId = jdbc.sql(QueryUtil.get(QueryConstants.GET_COUPON_SERVICE_CATEGORY))
            .param("serviceId", serviceId).query(Long.class).single();
        Order order = new Order(serviceId, categoryId, cityId, serviceAmount);
        Refusal refusal = CouponRules.check(c, order, usage(c, customerId, customerHasBooked(customerId)), clock.instant());
        if (refusal != null) {
            throw ApiException.unprocessable(refusal.code(), refusal.message());
        }
        BigDecimal discount = CouponRules.discount(c, serviceAmount);
        if (discount.signum() <= 0) {
            throw ApiException.unprocessable("COUPON_NOT_APPLICABLE", "This coupon gives no discount on this service");
        }
        return new AppliedCoupon(c.id(), c.code(), discount);
    }

    /** Records the use once the booking exists. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUse(AppliedCoupon applied, UUID bookingId, String bookingRef, UUID customerId) {
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_COUPON_REDEMPTION))
            .param("coupon", applied.couponId()).param("booking", bookingId).param("c", customerId)
            .param("amount", applied.discount()).update();
        log.info("Coupon {} applied to booking {} (discount {})", applied.code(), bookingRef, applied.discount());
    }

    /** The booking ended before the job started (cancelled, declined, expired): the customer gets the use back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(UUID bookingId, String bookingRef) {
        int rows = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_COUPON_RELEASE)).param("b", bookingId).update();
        if (rows > 0) {
            log.info("Coupon use given back: booking {} ended before the job started", bookingRef);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Order order(UUID customerId, UUID genieId, long serviceId, UUID addressId) {
        OrderRow row = jdbc.sql(QueryUtil.get(QueryConstants.GET_COUPON_CONTEXT))
            .param("customerId", customerId).param("genieId", genieId).param("serviceId", serviceId)
            .param("addressId", addressId)
            .query(OrderRow.class).optional()
            .orElseThrow(() -> ApiException.notFound("QUOTE_UNAVAILABLE", "This Genie does not offer that service at that address"));
        return new Order(row.serviceId(), row.categoryId(), row.cityId(), row.serviceAmount());
    }

    private Coupon find(String code, boolean lock) {
        String normalised = CouponRules.normalise(code);
        if (!StringUtils.hasText(normalised)) {
            throw ApiException.badRequest("COUPON_CODE_REQUIRED", "Enter a coupon code");
        }
        return jdbc.sql(QueryUtil.get(lock ? QueryConstants.LOCK_COUPON_BY_CODE : QueryConstants.GET_COUPON_BY_CODE))
            .param("code", normalised).query(Coupon.class).optional()
            .orElseThrow(() -> ApiException.notFound("COUPON_NOT_FOUND", "That coupon code doesn't exist"));
    }

    private Usage usage(Coupon c, UUID customerId, boolean customerHasBooked) {
        long total = c.totalLimit() == null ? 0 : jdbc.sql(QueryUtil.get(QueryConstants.COUNT_COUPON_USES))
            .param("id", c.id()).query(Long.class).single();
        long mine = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_COUPON_CUSTOMER_USES))
            .param("id", c.id()).param("c", customerId).query(Long.class).single();
        return new Usage(total, mine, customerHasBooked);
    }

    private boolean customerHasBooked(UUID customerId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_COUPON_CUSTOMER_BOOKING))
            .param("c", customerId).query(Boolean.class).single();
    }

    private static CouponOfferDto offer(Coupon c, Order order, Usage usage, Instant now) {
        Refusal refusal = CouponRules.check(c, order, usage, now);
        BigDecimal discount = CouponRules.discount(c, order.serviceAmount());
        return new CouponOfferDto(c.code(), c.title(), c.description(), c.discountType(), c.discountValue(),
            c.maxDiscount(), c.minOrder(), c.validUntil(), c.firstBookingOnly(), discount, refusal == null,
            refusal == null ? null : refusal.code(), refusal == null ? null : refusal.message());
    }
}
