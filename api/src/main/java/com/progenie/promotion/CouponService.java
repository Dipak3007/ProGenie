package com.progenie.promotion;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.progenie.promotion.CouponRules.Coupon;
import com.progenie.promotion.CouponRules.Order;
import com.progenie.promotion.CouponRules.OrderLine;
import com.progenie.promotion.CouponRules.Refusal;
import com.progenie.promotion.CouponRules.Usage;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.ServiceLines.ServiceLine;
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

    /**
     * A coupon applied to a new booking: store {@code couponId} and {@code discount} on it, then call {@link #recordUse}.
     * {@code coveredServiceIds}: the booking's services it covers, over which the discount is spread (Phase 3.5C).
     */
    public record AppliedCoupon(long couponId, String code, BigDecimal discount, Set<Long> coveredServiceIds) {
    }

    /** A service on a booking and its amount (price × quantity), as the coupon sees it. */
    public record CouponLine(long serviceId, BigDecimal amount) {
    }

    record OrderRow(BigDecimal unitPrice, long serviceId, long categoryId, long cityId) {
    }

    record ServiceCategory(long id, long categoryId) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;

    public CouponService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Public coupons for this checkout (Genie, services, address), each with its discount or why it doesn't apply. */
    @Transactional(readOnly = true)
    public List<CouponOfferDto> available(UUID customerId, @Nullable UUID genieId, List<ServiceLine> lines, UUID addressId) {
        Order order = order(customerId, genieId, lines, addressId);
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
    public CouponOfferDto check(UUID customerId, String code, @Nullable UUID genieId, List<ServiceLine> lines, UUID addressId) {
        Order order = order(customerId, genieId, lines, addressId);
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
    public AppliedCoupon apply(UUID customerId, String code, List<CouponLine> lines, long cityId) {
        Coupon c = find(code, true);
        Order order = order(lines, cityId);
        Refusal refusal = refusal(c, customerId, order);
        if (refusal != null) {
            throw ApiException.unprocessable(refusal.code(), refusal.message());
        }
        return applied(c, order);
    }

    /**
     * A service was removed from a booking (Phase 3.5C): the coupon's discount on what is left, without counting
     * uses again. Empty when it no longer applies (no service it covers, or under its minimum order); the use is
     * then given back. Otherwise the recorded use takes the new discount.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AppliedCoupon> reprice(long couponId, UUID bookingId, List<CouponLine> lines, long cityId) {
        Coupon c = jdbc.sql(QueryUtil.get(QueryConstants.GET_COUPON_BY_ID)).param("id", couponId)
            .query(Coupon.class).single();
        Order order = order(lines, cityId);
        boolean applies = CouponRules.inScope(c, order)
            && CouponRules.eligibleAmount(c, order).compareTo(c.minOrder()) >= 0
            && CouponRules.discount(c, order).signum() > 0;
        if (!applies) {
            jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_COUPON_RELEASE)).param("b", bookingId).update();
            log.info("Coupon {} no longer applies after a service was removed: use given back", c.code());
            return Optional.empty();
        }
        AppliedCoupon applied = applied(c, order);
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_COUPON_REDEMPTION_AMOUNT)).param("b", bookingId)
            .param("amount", applied.discount()).update();
        return Optional.of(applied);
    }

    /**
     * Like {@link #apply} but never throws (so the surrounding transaction stays usable): empty when the code is
     * unknown or no longer applies. Genie Now (Phase 3C-9) checks the coupon when the search starts and applies it
     * here to the accepting Genie's price; if it stopped applying in between, the booking goes ahead without it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AppliedCoupon> tryApply(UUID customerId, String code, long serviceId, long cityId, BigDecimal serviceAmount) {
        String normalised = CouponRules.normalise(code);
        if (!StringUtils.hasText(normalised)) {
            return Optional.empty();
        }
        Optional<Coupon> found = jdbc.sql(QueryUtil.get(QueryConstants.LOCK_COUPON_BY_CODE))
            .param("code", normalised).query(Coupon.class).optional();
        Order order = order(List.of(new CouponLine(serviceId, serviceAmount)), cityId);
        if (found.isEmpty() || refusal(found.get(), customerId, order) != null) {
            return Optional.empty();
        }
        return Optional.of(applied(found.get(), order));
    }

    private AppliedCoupon applied(Coupon c, Order order) {
        Set<Long> covered = order.lines().stream().filter(l -> CouponRules.covers(c, l, order.cityId()))
            .map(OrderLine::serviceId).collect(Collectors.toUnmodifiableSet());
        return new AppliedCoupon(c.id(), c.code(), CouponRules.discount(c, order), covered);
    }

    private @Nullable Refusal refusal(Coupon c, UUID customerId, Order order) {
        Refusal refusal = CouponRules.check(c, order, usage(c, customerId, customerHasBooked(customerId)), clock.instant());
        if (refusal != null) {
            return refusal;
        }
        if (CouponRules.discount(c, order).signum() <= 0) {
            return new Refusal("COUPON_NOT_APPLICABLE", "This coupon gives no discount on this service");
        }
        return null;
    }

    /** The booking's lines with their services' categories. */
    private Order order(List<CouponLine> lines, long cityId) {
        Map<Long, Long> categories = jdbc.sql(QueryUtil.get(QueryConstants.GET_COUPON_SERVICE_CATEGORIES))
            .param("ids", lines.stream().map(CouponLine::serviceId).toList())
            .query(ServiceCategory.class).list().stream()
            .collect(Collectors.toMap(ServiceCategory::id, ServiceCategory::categoryId));
        return new Order(cityId, lines.stream()
            .map(l -> new OrderLine(l.serviceId(), categories.getOrDefault(l.serviceId(), 0L), l.amount())).toList());
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

    /**
     * The order a coupon is checked against: the Genie's price × quantity for each service. Without a Genie (Genie
     * Now, Phase 3C-9: the Genie is not known yet) the service's base price is used; the booking applies the coupon to
     * the accepting Genie's price.
     */
    private Order order(UUID customerId, @Nullable UUID genieId, List<ServiceLine> lines, UUID addressId) {
        Map<Long, OrderRow> rows = jdbc.sql(QueryUtil.get(genieId == null ? QueryConstants.GET_COUPON_CONTEXT_SERVICE
                : QueryConstants.GET_COUPON_CONTEXT))
            .param("customerId", customerId).param("genieId", genieId)
            .param("ids", lines.stream().map(ServiceLine::serviceId).toList())
            .param("addressId", addressId)
            .query(OrderRow.class).list().stream()
            .collect(Collectors.toMap(OrderRow::serviceId, r -> r));
        if (rows.size() != lines.size()) {
            throw ApiException.notFound("QUOTE_UNAVAILABLE", "This Genie does not offer that service at that address");
        }
        long cityId = rows.values().iterator().next().cityId();
        return new Order(cityId, lines.stream().map(l -> {
            OrderRow r = rows.get(l.serviceId());
            return new OrderLine(r.serviceId(), r.categoryId(), r.unitPrice().multiply(BigDecimal.valueOf(l.quantity())));
        }).toList());
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
        BigDecimal discount = CouponRules.discount(c, order);
        return new CouponOfferDto(c.code(), c.title(), c.description(), c.discountType(), c.discountValue(),
            c.maxDiscount(), c.minOrder(), c.validUntil(), c.firstBookingOnly(), discount, refusal == null,
            refusal == null ? null : refusal.code(), refusal == null ? null : refusal.message());
    }
}
