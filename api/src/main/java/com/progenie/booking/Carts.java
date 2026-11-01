package com.progenie.booking;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

import com.progenie.booking.CartDtos.CartDto;
import com.progenie.booking.CartDtos.CartItemDto;
import com.progenie.booking.CartDtos.CartRequest;
import com.progenie.booking.CartDtos.CartResponse;
import com.progenie.customer.AddressDtos.AddressDto;
import com.progenie.customer.AddressService;
import com.progenie.pricing.PriceCalculator.PriceBreakdown;
import com.progenie.pricing.PriceCalculator.ServicePrice;
import com.progenie.pricing.PricingService;
import com.progenie.promotion.CouponService;
import com.progenie.provider.GenieDirectory;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.ServiceLines;
import com.progenie.shared.util.ServiceLines.ServiceLine;
import com.progenie.shared.util.Times;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The customer's cart (Phase 3.5E): one saved checkout per customer, for one Genie. Checkout saves it as the customer
 * goes; reading it re-checks everything (the Genie still takes bookings, each service is still offered, the address is
 * still served, the time is still free, the coupon still applies) and reports what changed, including the price.
 * Deleted when the booking is made, when emptied or replaced, or 30 days after it was last saved; one in-app reminder
 * a day after it was last saved.
 */
@Service
public class Carts {

    private static final Logger log = LoggerFactory.getLogger(Carts.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);

    record Row(UUID customerId, UUID genieId, String genieName, String items, @Nullable UUID addressId,
               @Nullable OffsetDateTime slotStart, @Nullable String paymentMethod, BigDecimal tipAmount,
               @Nullable String notes, @Nullable String couponCode, @Nullable BigDecimal seenTotal,
               OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    record Due(UUID customerId, String genieName, String items) {
    }

    record ServiceName(long id, String name) {
    }

    private final JdbcClient jdbc;
    private final GenieDirectory directory;
    private final AddressService addresses;
    private final PricingService pricing;
    private final CouponService coupons;
    private final SlotService slots;
    private final JobLock lock;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ZoneId zone;
    private final Duration ttl;
    private final Duration reminderAfter;

    public Carts(JdbcClient jdbc, GenieDirectory directory, AddressService addresses, PricingService pricing,
                 CouponService coupons, SlotService slots, JobLock lock, ApplicationEventPublisher events, Clock clock,
                 AppProperties props, @Value("${progenie.cart.ttl:30d}") Duration ttl,
                 @Value("${progenie.cart.reminder-after:24h}") Duration reminderAfter) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.addresses = addresses;
        this.pricing = pricing;
        this.coupons = coupons;
        this.slots = slots;
        this.lock = lock;
        this.events = events;
        this.clock = clock;
        this.zone = ZoneId.of(props.timezone());
        this.ttl = ttl;
        this.reminderAfter = reminderAfter;
    }

    /**
     * The cart, re-checked; what the re-check changed is saved and listed in {@code notices}. Not one transaction on
     * purpose: each check may fail (and roll back) on its own, and the cart is then corrected.
     */
    public CartResponse get(UUID customerId) {
        return recheck(customerId, new ArrayList<>());
    }

    /**
     * Saves the checkout as it is now. The Genie and services must be bookable (404/422 as for a booking) and the
     * address the customer's own. A cart for another Genie is replaced only with {@code replace} (409 CART_OTHER_GENIE,
     * with {@code genieName}, otherwise).
     */
    public CartResponse save(UUID customerId, CartRequest req) {
        List<ServiceLine> lines = ServiceLines.of(null, req.items());
        directory.bookableOffer(req.genieId(), lines.getFirst().serviceId());
        pricing.servicePrices(req.genieId(), lines);
        if (req.addressId() != null) {
            addresses.getOwned(customerId, req.addressId());
        }
        Row current = row(customerId);
        if (current != null && !current.genieId().equals(req.genieId()) && !req.replace()) {
            throw ApiException.conflict("CART_OTHER_GENIE", "Your cart has a booking with " + current.genieName()
                + ". Starting one with another Genie will clear it.").with("genieName", current.genieName());
        }
        jdbc.sql(QueryUtil.get(QueryConstants.UPSERT_CART))
            .param("u", customerId)
            .param("genieId", req.genieId())
            .param("items", ServiceLines.format(lines))
            .param("addressId", req.addressId())
            .param("slotStart", req.slotStart())
            .param("paymentMethod", req.paymentMethod() == null ? null : req.paymentMethod().name())
            .param("tip", req.tipAmount() == null ? BigDecimal.ZERO : req.tipAmount())
            .param("notes", StringUtils.hasText(req.notes()) ? req.notes().trim() : null)
            .param("couponCode", StringUtils.hasText(req.couponCode()) ? req.couponCode().trim().toUpperCase(Locale.ROOT) : null)
            .param("seenTotal", req.seenTotal())
            .update();
        return recheck(customerId, new ArrayList<>());
    }

    public void clear(UUID customerId) {
        jdbc.sql(QueryUtil.get(QueryConstants.DELETE_CART)).param("u", customerId).update();
    }

    /** After a booking with this Genie is made, the cart that led to it is done (inside the booking's transaction). */
    void booked(UUID customerId, UUID genieId) {
        jdbc.sql(QueryUtil.get(QueryConstants.DELETE_CART_BOOKED)).param("u", customerId).param("genieId", genieId).update();
    }

    private CartResponse recheck(UUID customerId, List<String> notices) {
        Row r = row(customerId);
        if (r == null) {
            return new CartResponse(null, notices);
        }
        Instant now = clock.instant();
        if (r.updatedAt().toInstant().plus(ttl).isBefore(now)) {
            clear(customerId);
            return new CartResponse(null, notices);
        }
        String first = r.genieName().split(" ")[0];
        List<ServiceLine> lines = ServiceLines.parse(null, r.items());
        try {
            directory.bookableOffer(r.genieId(), lines.getFirst().serviceId());
        } catch (ApiException e) {
            if (!"SERVICE_NOT_OFFERED".equals(e.getCode())) {
                clear(customerId);
                notices.add(r.genieName() + " isn't taking bookings right now, so your cart was emptied.");
                return new CartResponse(null, notices);
            }
        }

        // services the Genie still offers, at today's price
        List<ServiceLine> kept = new ArrayList<>();
        List<ServicePrice> prices = new ArrayList<>();
        List<Long> dropped = new ArrayList<>();
        for (ServiceLine l : lines) {
            try {
                prices.add(pricing.servicePrices(r.genieId(), List.of(l)).getFirst());
                kept.add(l);
            } catch (ApiException e) {
                dropped.add(l.serviceId());
            }
        }
        if (!dropped.isEmpty()) {
            Map<Long, String> names = serviceNames(dropped);
            for (Long id : dropped) {
                notices.add(names.getOrDefault(id, "A service") + " is no longer offered by " + first + " and was removed.");
            }
        }
        if (kept.isEmpty()) {
            clear(customerId);
            notices.add("Your cart was emptied.");
            return new CartResponse(null, notices);
        }

        // the address must still be served by this Genie
        AddressDto address = null;
        if (r.addressId() != null) {
            try {
                address = addresses.getOwned(customerId, r.addressId());
                if (!directory.servesCity(r.genieId(), address.cityId())
                    || !directory.servesLocation(r.genieId(), address.lat(), address.lng())) {
                    notices.add(first + " doesn't travel to " + address.label() + " any more. Please pick another address.");
                    address = null;
                }
            } catch (ApiException e) {
                address = null;
            }
        }

        boolean inspection = prices.stream().anyMatch(ServicePrice::inspectionFirst);
        int minutes;
        try {
            minutes = pricing.durationMinutes(r.genieId(), kept);
        } catch (ApiException e) {
            minutes = 0;
        }
        PriceBreakdown estimate = null;
        if (address != null) {
            try {
                estimate = pricing.estimate(r.genieId(), kept, address.lat(), address.lng(), r.tipAmount());
                minutes = estimate.durationMinutes();
            } catch (ApiException e) {
                estimate = null;
            }
        }

        // the coupon, if it still applies (never on an inspection visit)
        String coupon = r.couponCode();
        BigDecimal discount = null;
        if (coupon != null && inspection) {
            notices.add("Coupons can't be used on an inspection visit, so " + coupon + " was removed.");
            coupon = null;
        } else if (coupon != null && address != null) {
            try {
                discount = coupons.check(customerId, coupon, r.genieId(), kept, address.id()).discount();
            } catch (ApiException e) {
                notices.add("Coupon " + coupon + " no longer applies: " + e.getMessage());
                coupon = null;
            }
        }

        // the time, if it is still free
        OffsetDateTime slot = r.slotStart();
        if (slot != null && minutes > 0) {
            try {
                slots.assertBookable(r.genieId(), slot.toInstant(), minutes, null);
            } catch (ApiException e) {
                notices.add("The time you picked (" + WHEN.format(slot.atZoneSameInstant(zone))
                    + ") is no longer available. Please pick another time.");
                slot = null;
            }
        }

        BigDecimal total = estimate == null ? null
            : estimate.totalAmount().subtract(discount == null ? BigDecimal.ZERO : discount).max(BigDecimal.ZERO);
        BigDecimal seen = r.seenTotal();
        if (total != null && seen != null && total.compareTo(seen) != 0) {
            notices.add("The price changed from " + rupees(seen) + " to " + rupees(total) + ".");
        }
        BigDecimal newSeen = total != null ? total : seen;
        String items = ServiceLines.format(kept);
        UUID addressId = address == null ? null : address.id();
        if (!items.equals(r.items()) || !Objects.equals(addressId, r.addressId()) || !Objects.equals(slot, r.slotStart())
            || !Objects.equals(coupon, r.couponCode()) || !sameMoney(newSeen, seen)) {
            jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_CART_RECHECKED))
                .param("u", customerId).param("items", items).param("addressId", addressId).param("slotStart", slot)
                .param("couponCode", coupon).param("seenTotal", newSeen)
                .update();
        }

        List<CartItemDto> dtoItems = new ArrayList<>();
        for (int i = 0; i < kept.size(); i++) {
            ServicePrice p = prices.get(i);
            int qty = kept.get(i).quantity();
            dtoItems.add(new CartItemDto(p.serviceId(), p.serviceName(), qty, p.unitPrice(),
                p.unitPrice().multiply(BigDecimal.valueOf(qty)), p.durationMinutes(), p.inspectionFirst()));
        }
        String label = address == null ? null : address.label() + " · " + address.line1();
        CartDto cart = new CartDto(r.genieId(), r.genieName(), dtoItems, items, addressId, label, slot,
            r.paymentMethod(), r.tipAmount(), r.notes(), coupon, discount, estimate, total, minutes, inspection,
            r.updatedAt(), r.updatedAt().plus(ttl));
        return new CartResponse(cart, notices);
    }

    /**
     * Hourly: one in-app reminder for carts left alone for a day ({@link CartReminderDue}), and carts not saved for 30
     * days are deleted. Interval and delay are settings so tests can run it quickly.
     */
    @Scheduled(fixedDelayString = "${progenie.cart.reminder-interval:PT1H}", initialDelayString = "${progenie.cart.reminder-interval:PT1H}")
    @Transactional
    public void remindAndExpire() {
        if (!lock.tryAcquire("cart-reminders")) {
            return;
        }
        Instant now = clock.instant();
        int expired = jdbc.sql(QueryUtil.get(QueryConstants.DELETE_CART_EXPIRED))
            .param("expiry", Times.odt(now.minus(ttl))).update();
        List<Due> due = jdbc.sql(QueryUtil.get(QueryConstants.LOCK_CART_DUE_REMINDERS))
            .param("before", Times.odt(now.minus(reminderAfter))).param("expiry", Times.odt(now.minus(ttl)))
            .query(Due.class).list();
        for (Due d : due) {
            jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_CART_REMINDED)).param("u", d.customerId()).update();
            List<ServiceLine> lines = ServiceLines.parse(null, d.items());
            Map<Long, String> names = serviceNames(lines.stream().map(ServiceLine::serviceId).toList());
            String label = lines.stream()
                .map(l -> names.getOrDefault(l.serviceId(), "a service") + (l.quantity() > 1 ? " ×" + l.quantity() : ""))
                .collect(Collectors.joining(" + "));
            events.publishEvent(new CartReminderDue(d.customerId(), d.genieName(), label));
        }
        if (expired > 0 || !due.isEmpty()) {
            log.info("Carts: {} reminder(s) queued, {} expired cart(s) deleted", due.size(), expired);
        }
    }

    private @Nullable Row row(UUID customerId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CART_ROW)).param("u", customerId).query(Row.class).optional().orElse(null);
    }

    private Map<Long, String> serviceNames(List<Long> ids) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CART_SERVICE_NAMES)).param("ids", ids)
            .query(ServiceName.class).list().stream().collect(Collectors.toMap(ServiceName::id, ServiceName::name));
    }

    private static boolean sameMoney(@Nullable BigDecimal a, @Nullable BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    static String rupees(BigDecimal amount) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.of("en", "IN"));
        boolean whole = amount.stripTrailingZeros().scale() <= 0;
        f.setMinimumFractionDigits(whole ? 0 : 2);
        f.setMaximumFractionDigits(2);
        return "₹" + f.format(amount);
    }
}
