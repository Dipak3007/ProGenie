package com.progenie.booking;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import com.progenie.booking.BookingDtos.BookingDetailDto;
import com.progenie.booking.BookingQueryService.Viewer;
import com.progenie.booking.domain.Booking;
import com.progenie.booking.domain.PaymentMethod;
import com.progenie.customer.AddressDtos.AddressDto;
import com.progenie.customer.AddressService;
import com.progenie.demand.DemandRecorder;
import com.progenie.identity.AccountGuards;
import com.progenie.legal.ConsentService;
import com.progenie.messaging.LiveHub;
import com.progenie.pricing.PriceCalculator.PriceBreakdown;
import com.progenie.pricing.PricingService;
import com.progenie.promotion.CouponService;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.Times;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Genie Now (Phase 3C-9): instead of picking a slot, the customer asks for the nearest free Genie right away.
 *
 * <p>Candidates: approved, online Genies (ProGenie open, see {@code GeniePresence}) who offer the service, work in
 * the city, cover the address with their service radius, are within their working hours for the whole job, and have
 * nothing booked (and no time off) in the next 2 hours. Nearest first (live position when recent, else base
 * location), then best rated.
 *
 * <p>One Genie at a time gets the offer, over the live stream, for 45 seconds; up to 5 Genies are asked. The first
 * to accept gets an ACCEPTED booking at their own price plus the city's instant fee. If nobody accepts, the request
 * ends as NO_GENIE: "No Genie is free right now. Book a time slot instead." Those requests are the unmet demand the
 * admin heatmap shows (Phase 3D). Searching is free to cancel.
 *
 * <p>Concurrency: every change to a request locks its row first (accept, decline, cancel and the expiry sweep), and
 * offers change only from OFFERED, so two replicas or a late accept can never book twice.
 */
@Service
public class InstantBookings {

    private static final Logger log = LoggerFactory.getLogger(InstantBookings.class);
    private static final UUID NO_REQUEST = new UUID(0, 0);
    /** An accept that reaches the server a moment after the countdown ended still counts. */
    private static final java.time.Duration ACCEPT_GRACE = java.time.Duration.ofSeconds(3);

    // ------------------------------------------------------------------ API records

    /** What the customer sees before searching: can it be booked now, and the price range across nearby Genies. */
    public record InstantQuoteDto(boolean available, @Nullable String reasonCode, @Nullable String reason,
                                  long serviceId, String serviceName, int durationMinutes, int genieCount,
                                  @Nullable BigDecimal priceFrom, @Nullable BigDecimal priceTo, BigDecimal instantFee,
                                  int arrivalMinutes, int offerSeconds, int maxGenies, @Nullable String noGenieReason) {
    }

    /**
     * Why nobody was found (Phase 3D), the first condition no Genie passed. Shown to the customer, kept on the
     * request and on unmet-demand events so admins can see what to fix (more Genies, longer hours, wider radius).
     */
    public enum Reason {
        NO_GENIE_FOR_SERVICE("No Genie offers this service in your city yet."),
        TOO_FAR("No Genie who offers this service can reach your address within an hour."),
        NONE_ONLINE("No Genie near you is online right now."),
        OUTSIDE_HOURS("The Genies near you have finished for the day, or the job would run past their working hours."),
        ALL_BUSY("All Genies near you are busy with other jobs."),
        NOBODY_ACCEPTED("The Genies we asked couldn't take it.");

        public final String text;

        Reason(String text) {
            this.text = text;
        }
    }

    public record InstantRequestBody(@NotNull Long serviceId, @NotNull UUID addressId, @NotNull PaymentMethod paymentMethod,
                                     @DecimalMin("0") @DecimalMax("2000") BigDecimal tipAmount,
                                     @Size(max = 500) String notes, @Size(max = 30) String couponCode) {
    }

    /** A search. {@code status}: SEARCHING, BOOKED (see {@code bookingId}), NO_GENIE or CANCELLED. */
    public record InstantRequestDto(UUID id, String status, long serviceId, String serviceName, OffsetDateTime createdAt,
                                    @Nullable OffsetDateTime endedAt, int geniesAsked, int maxGenies,
                                    @Nullable OffsetDateTime offerExpiresAt, @Nullable UUID bookingId, String message,
                                    @Nullable String noGenieReason) {
    }

    /** An offer as the Genie sees it (live event {@code instant_offer} and GET /genie/instant-offers). */
    public record InstantOfferDto(long id, UUID requestId, String serviceName, int durationMinutes, @Nullable String area,
                                  String city, BigDecimal distanceKm, @Nullable Integer travelMinutes, BigDecimal earning,
                                  String paymentMethod,
                                  OffsetDateTime expiresAt, @Nullable String notes) {
    }

    /** Live event to a Genie when their offer is gone: {@code change} EXPIRED, WITHDRAWN (customer cancelled) or TAKEN. */
    public record OfferGone(long offerId, String change) {
    }

    // ------------------------------------------------------------------ rows

    record ServiceRow(long id, String name, int durationMinutes, boolean active, boolean instantEnabled) {
    }

    record Candidate(UUID genieId, BigDecimal distanceKm, BigDecimal avgRating, int travelMinutes) {
    }

    record Funnel(int offering, int inRange, int online, int inHours, int free) {

        Reason reason() {
            return offering == 0 ? Reason.NO_GENIE_FOR_SERVICE : inRange == 0 ? Reason.TOO_FAR : online == 0 ? Reason.NONE_ONLINE
                : inHours == 0 ? Reason.OUTSIDE_HOURS : Reason.ALL_BUSY;
        }
    }

    record RequestRow(UUID id, UUID customerId, long serviceId, long cityId, @Nullable UUID addressId, double lat,
                      double lng, String paymentMethod, BigDecimal tipAmount, @Nullable String notes,
                      @Nullable String couponCode, String status) {
    }

    record RequestView(UUID id, String status, long serviceId, String serviceName, OffsetDateTime createdAt,
                       @Nullable OffsetDateTime endedAt, int geniesAsked, @Nullable OffsetDateTime offerExpiresAt,
                       @Nullable UUID bookingId, UUID customerId, @Nullable String noGenieReason) {
    }

    record OfferRow(long id, UUID requestId, UUID genieId, String status, OffsetDateTime expiresAt,
                    @Nullable Integer travelMinutes) {
    }

    record ExpiredOffer(long id, UUID requestId) {
    }

    record Withdrawn(long id, UUID genieId) {
    }

    // ------------------------------------------------------------------

    private final JdbcClient jdbc;
    private final InstantProperties props;
    private final BookingService bookings;
    private final BookingQueryService queries;
    private final AddressService addresses;
    private final PricingService pricing;
    private final CouponService coupons;
    private final AccountGuards guards;
    private final ConsentService consents;
    private final LiveHub live;
    private final DemandRecorder demand;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final ZoneId zone;

    public InstantBookings(JdbcClient jdbc, InstantProperties props, BookingService bookings, BookingQueryService queries,
                           AddressService addresses, PricingService pricing, CouponService coupons, AccountGuards guards,
                           ConsentService consents, LiveHub live, PlatformTransactionManager txManager, Clock clock,
                           AppProperties app, DemandRecorder demand) {
        this.demand = demand;
        this.jdbc = jdbc;
        this.props = props;
        this.bookings = bookings;
        this.queries = queries;
        this.addresses = addresses;
        this.pricing = pricing;
        this.coupons = coupons;
        this.guards = guards;
        this.consents = consents;
        this.live = live;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.zone = ZoneId.of(app.timezone());
    }

    // ------------------------------------------------------------------ customer

    /** Can this service be booked now at this address, and for roughly how much (nearest Genies' prices)? */
    @Transactional(readOnly = true)
    public InstantQuoteDto quote(UUID customerId, long serviceId, UUID addressId) {
        ServiceRow s = service(serviceId);
        AddressDto a = addresses.getOwned(customerId, addressId);
        BigDecimal fee = pricing.instantFee(a.cityId());
        if (!s.active() || !s.instantEnabled()) {
            return quote(s, false, "INSTANT_NOT_AVAILABLE", "This service can't be booked right away. Pick a time slot instead.",
                0, null, null, fee);
        }
        List<Candidate> nearby = candidates(s, a.cityId(), a.lat(), a.lng(), customerId, NO_REQUEST, 10);
        if (nearby.isEmpty()) {
            Reason why = funnel(s, a.cityId(), a.lat(), a.lng(), customerId, NO_REQUEST).reason();
            demand.record(new DemandRecorder.Event(DemandRecorder.Kind.INSTANT_NONE_FREE, a.cityId(), null, s.id(),
                a.pincode(), a.lat(), a.lng(), why.name()), customerId.toString());
            InstantQuoteDto none = quote(s, false, "NO_GENIE_NEARBY", why.text + " Book a time slot instead.", 0, null, null, fee);
            return withReason(none, why);
        }
        BigDecimal from = null;
        BigDecimal to = null;
        for (Candidate c : nearby) {
            BigDecimal total = pricing.estimate(c.genieId(), serviceId, a.lat(), a.lng(), BigDecimal.ZERO).totalAmount().add(fee);
            from = from == null || total.compareTo(from) < 0 ? total : from;
            to = to == null || total.compareTo(to) > 0 ? total : to;
        }
        return quote(s, true, null, null, nearby.size(), from, to, fee);
    }

    /** Starts a search: the first Genie is asked at once (or the request ends as NO_GENIE when nobody is free). */
    @Transactional
    public InstantRequestDto request(UUID customerId, InstantRequestBody req) {
        guards.requireVerifiedPhone(customerId);
        consents.requireNoPending(customerId, "CUSTOMER");
        if (queries.hasOutstandingFee(customerId)) {
            throw ApiException.unprocessable("OUTSTANDING_FEE", "Please pay your pending cancellation fee before booking again");
        }
        ServiceRow s = service(req.serviceId());
        if (!s.active() || !s.instantEnabled()) {
            throw ApiException.unprocessable("INSTANT_NOT_AVAILABLE", "This service can't be booked right away. Pick a time slot instead.");
        }
        addresses.getOwned(customerId, req.addressId());
        if (Boolean.TRUE.equals(jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_INSTANT_SEARCHING))
            .param("customerId", customerId).query(Boolean.class).single())) {
            throw ApiException.conflict("INSTANT_IN_PROGRESS", "You're already looking for a Genie. Wait for it to finish or cancel it.");
        }
        String coupon = StringUtils.hasText(req.couponCode()) ? req.couponCode().trim() : null;
        if (coupon != null) {
            coupons.check(customerId, coupon, null, s.id(), req.addressId()); // 404/422 with the reason
        }
        UUID id = jdbc.sql(QueryUtil.get(QueryConstants.INSERT_INSTANT_REQUEST))
            .param("customerId", customerId).param("serviceId", s.id()).param("addressId", req.addressId())
            .param("method", req.paymentMethod().name())
            .param("tip", req.tipAmount() == null ? BigDecimal.ZERO : req.tipAmount())
            .param("notes", StringUtils.hasText(req.notes()) ? req.notes().trim() : null)
            .param("coupon", coupon)
            .query(UUID.class).single();
        log.info("Genie Now request {} by {} for service {}", id, customerId, s.id());
        advance(lock(id));
        return view(id);
    }

    @Transactional(readOnly = true)
    public InstantRequestDto get(UUID customerId, UUID requestId) {
        RequestView v = viewRow(requestId);
        if (!v.customerId().equals(customerId)) {
            throw notFound();
        }
        return toDto(v);
    }

    /** Free while searching. A request that was already booked or ended is returned as it is. */
    @Transactional
    public InstantRequestDto cancel(UUID customerId, UUID requestId) {
        RequestRow r = lock(requestId);
        if (!r.customerId().equals(customerId)) {
            throw notFound();
        }
        if ("SEARCHING".equals(r.status())) {
            withdrawOffers(r.id());
            end(r.id(), "CANCELLED", null);
            log.info("Genie Now request {} cancelled by the customer", r.id());
        }
        return view(requestId);
    }

    // ------------------------------------------------------------------ Genie

    @Transactional(readOnly = true)
    public List<InstantOfferDto> openOffers(UUID genieId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_INSTANT_GENIE_OPEN_OFFERS))
            .param("genieId", genieId).param("now", Times.odt(clock.instant()))
            .query(InstantOfferDto.class).list();
    }

    /** The first to accept gets the job: an ACCEPTED booking. 409 OFFER_GONE when it expired or the customer cancelled. */
    @Transactional
    public BookingDetailDto accept(UUID genieId, long offerId) {
        OfferRow first = offer(offerId, genieId);
        RequestRow r = lock(first.requestId());
        OfferRow o = offer(offerId, genieId);
        Instant now = clock.instant();
        if (!"OFFERED".equals(o.status()) || !"SEARCHING".equals(r.status())
            || !now.isBefore(o.expiresAt().toInstant().plus(ACCEPT_GRACE))) {
            throw ApiException.conflict("OFFER_GONE", "This job is no longer available");
        }
        if (r.addressId() == null) {
            throw ApiException.conflict("OFFER_GONE", "This job is no longer available");
        }
        Booking b = bookings.createInstant(new BookingService.InstantOrder(r.id(), r.customerId(), genieId, r.serviceId(),
            r.cityId(), r.addressId(), r.lat(), r.lng(), PaymentMethod.valueOf(r.paymentMethod()), r.tipAmount(), r.notes(),
            r.couponCode(), o.travelMinutes() == null ? props.arrivalWindow() : java.time.Duration.ofMinutes(o.travelMinutes())));
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_INSTANT_OFFER_STATUS)).param("id", offerId).param("status", "ACCEPTED").update();
        end(r.id(), "BOOKED", b.getId());
        log.info("Genie Now request {} booked as {} by Genie {}", r.id(), b.getBookingRef(), genieId);
        return queries.detail(b.getId(), Viewer.GENIE, genieId);
    }

    /** Passes on the job; the next Genie is asked straight away. */
    @Transactional
    public void decline(UUID genieId, long offerId) {
        OfferRow first = offer(offerId, genieId);
        RequestRow r = lock(first.requestId());
        int changed = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_INSTANT_OFFER_STATUS))
            .param("id", offerId).param("status", "DECLINED").update();
        if (changed == 1 && "SEARCHING".equals(r.status())) {
            log.info("Genie Now request {}: Genie {} declined", r.id(), genieId);
            advance(r);
        }
    }

    // ------------------------------------------------------------------ expiry

    /**
     * Moves expired offers on to the next Genie, and restarts any search left without an open offer. Each request is
     * handled in its own transaction under its row lock, so replicas running this at the same time do no harm.
     */
    @Scheduled(fixedDelayString = "${progenie.instant.sweep-interval:5s}", initialDelayString = "10s")
    public void sweep() {
        Instant now = clock.instant();
        List<ExpiredOffer> expired = jdbc.sql(QueryUtil.get(QueryConstants.GET_INSTANT_OFFERS_EXPIRED))
            .param("now", Times.odt(now)).query(ExpiredOffer.class).list();
        for (ExpiredOffer e : expired) {
            runSafely(e.requestId(), () -> {
                RequestRow r = lock(e.requestId());
                OfferRow o = jdbc.sql(QueryUtil.get(QueryConstants.GET_INSTANT_OFFER)).param("id", e.id())
                    .query(OfferRow.class).single();
                if (!"OFFERED".equals(o.status()) || o.expiresAt().toInstant().isAfter(clock.instant())) {
                    return;
                }
                jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_INSTANT_OFFER_STATUS)).param("id", o.id()).param("status", "EXPIRED").update();
                live.afterCommit(() -> live.toUser(o.genieId(), "instant_offer", new OfferGone(o.id(), "EXPIRED")));
                if ("SEARCHING".equals(r.status())) {
                    advance(r);
                }
            });
        }
        List<UUID> stalled = jdbc.sql(QueryUtil.get(QueryConstants.GET_INSTANT_REQUESTS_STALLED))
            .param("before", Times.odt(now.minusSeconds(10))).query(UUID.class).list();
        for (UUID id : stalled) {
            runSafely(id, () -> {
                RequestRow r = lock(id);
                if ("SEARCHING".equals(r.status())) {
                    advance(r);
                }
            });
        }
    }

    private void runSafely(UUID requestId, Runnable work) {
        try {
            tx.executeWithoutResult(status -> work.run());
        } catch (RuntimeException ex) {
            log.warn("Genie Now request {}: could not move on ({})", requestId, ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ the search

    /** Asks the next Genie, or ends the search. The request row must be locked by the caller. */
    private void advance(RequestRow r) {
        int asked = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_INSTANT_OFFERS)).param("requestId", r.id())
            .query(Integer.class).single();
        ServiceRow s = service(r.serviceId());
        List<Candidate> next = asked >= props.maxGenies() || !s.active() || !s.instantEnabled()
            ? List.of()
            : candidates(s, r.cityId(), r.lat(), r.lng(), r.customerId(), r.id(), 1);
        if (next.isEmpty()) {
            Reason why = asked > 0 ? Reason.NOBODY_ACCEPTED
                : !s.active() || !s.instantEnabled() ? Reason.NO_GENIE_FOR_SERVICE
                : funnel(s, r.cityId(), r.lat(), r.lng(), r.customerId(), r.id()).reason();
            end(r.id(), "NO_GENIE", null, why);
            log.info("Genie Now request {}: no Genie ({} asked, {})", r.id(), asked, why);
            return;
        }
        Candidate c = next.getFirst();
        PriceBreakdown quote = pricing.estimate(c.genieId(), r.serviceId(), r.lat(), r.lng(), r.tipAmount());
        BigDecimal earning = quote.genieEarning().add(pricing.instantFee(r.cityId()));
        Instant now = clock.instant();
        long offerId = jdbc.sql(QueryUtil.get(QueryConstants.INSERT_INSTANT_OFFER))
            .param("requestId", r.id()).param("genieId", c.genieId()).param("rank", asked + 1)
            .param("km", c.distanceKm()).param("earning", earning).param("travel", c.travelMinutes())
            .param("now", Times.odt(now)).param("expires", Times.odt(now.plus(props.offerTimeout())))
            .query(Long.class).single();
        InstantOfferDto offer = jdbc.sql(QueryUtil.get(QueryConstants.GET_INSTANT_OFFER_VIEW)).param("id", offerId)
            .query(InstantOfferDto.class).single();
        InstantRequestDto view = view(r.id());
        log.info("Genie Now request {}: offered to Genie {} ({} of {}, {} km)", r.id(), c.genieId(), asked + 1,
            props.maxGenies(), c.distanceKm());
        live.afterCommit(() -> {
            live.toUser(c.genieId(), "instant_offer", offer);
            live.toUser(r.customerId(), "instant", view);
        });
    }

    private void end(UUID requestId, String status, @Nullable UUID bookingId) {
        end(requestId, status, bookingId, null);
    }

    private void end(UUID requestId, String status, @Nullable UUID bookingId, @Nullable Reason reason) {
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_INSTANT_REQUEST_ENDED))
            .param("id", requestId).param("status", status).param("bookingId", bookingId)
            .param("reason", reason == null ? null : reason.name()).update();
        RequestView row = viewRow(requestId);
        InstantRequestDto view = toDto(row);
        live.afterCommit(() -> live.toUser(row.customerId(), "instant", view));
    }

    private void withdrawOffers(UUID requestId) {
        List<Withdrawn> gone = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_INSTANT_OFFERS_WITHDRAW))
            .param("requestId", requestId).query(Withdrawn.class).list();
        live.afterCommit(() -> gone.forEach(w -> live.toUser(w.genieId(), "instant_offer", new OfferGone(w.id(), "WITHDRAWN"))));
    }

    /**
     * Free Genies for the job, nearest first. See the class comment for the rules. "Within working hours" uses each
     * Genie's own travel estimate (straight line × 1.3 at 22 km/h, plus 10 minutes to get going, at least 15), and a
     * Genie who can't arrive within the arrival window (an hour) is not asked.
     */
    private List<Candidate> candidates(ServiceRow s, long cityId, double lat, double lng, UUID customerId, UUID requestId,
                                       int limit) {
        return pool(QueryConstants.GET_INSTANT_CANDIDATES, s, cityId, lat, lng, customerId, requestId)
            .param("limit", limit).query(Candidate.class).list();
    }

    /** How many Genies pass each condition, to say why nobody was found. */
    private Funnel funnel(ServiceRow s, long cityId, double lat, double lng, UUID customerId, UUID requestId) {
        return pool(QueryConstants.GET_INSTANT_FUNNEL, s, cityId, lat, lng, customerId, requestId).query(Funnel.class).single();
    }

    private JdbcClient.StatementSpec pool(String key, ServiceRow s, long cityId, double lat, double lng, UUID customerId,
                                          UUID requestId) {
        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(zone);
        return jdbc.sql(QueryUtil.get(key))
            .param("serviceId", s.id()).param("cityId", cityId).param("lat", lat).param("lng", lng)
            .param("customerId", customerId).param("requestId", requestId)
            .param("now", Times.odt(now)).param("fresh", Times.odt(now.minus(props.positionMaxAge())))
            .param("busyUntil", Times.odt(now.plus(props.busyWindow())))
            .param("arrivalMinutes", (int) props.arrivalWindow().toMinutes())
            .param("dow", local.getDayOfWeek().getValue()).param("timeNow", local.toLocalTime().withNano(0));
    }

    // ------------------------------------------------------------------ helpers

    private InstantQuoteDto quote(ServiceRow s, boolean available, @Nullable String code, @Nullable String reason, int count,
                                  @Nullable BigDecimal from, @Nullable BigDecimal to, BigDecimal fee) {
        return new InstantQuoteDto(available, code, reason, s.id(), s.name(), s.durationMinutes(), count, from, to, fee,
            (int) props.arrivalWindow().toMinutes(), (int) props.offerTimeout().toSeconds(), props.maxGenies(), null);
    }

    private static InstantQuoteDto withReason(InstantQuoteDto q, Reason why) {
        return new InstantQuoteDto(q.available(), q.reasonCode(), q.reason(), q.serviceId(), q.serviceName(),
            q.durationMinutes(), q.genieCount(), q.priceFrom(), q.priceTo(), q.instantFee(), q.arrivalMinutes(),
            q.offerSeconds(), q.maxGenies(), why.name());
    }

    private ServiceRow service(long id) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_INSTANT_SERVICE)).param("id", id).query(ServiceRow.class)
            .optional().orElseThrow(() -> ApiException.notFound("SERVICE_NOT_FOUND", "Service not found"));
    }

    private RequestRow lock(UUID id) {
        return jdbc.sql(QueryUtil.get(QueryConstants.LOCK_INSTANT_REQUEST)).param("id", id).query(RequestRow.class)
            .optional().orElseThrow(InstantBookings::notFound);
    }

    private OfferRow offer(long id, UUID genieId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_INSTANT_OFFER)).param("id", id).query(OfferRow.class).optional()
            .filter(o -> o.genieId().equals(genieId))
            .orElseThrow(() -> ApiException.notFound("OFFER_NOT_FOUND", "Offer not found"));
    }

    private RequestView viewRow(UUID id) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_INSTANT_REQUEST)).param("id", id).query(RequestView.class)
            .optional().orElseThrow(InstantBookings::notFound);
    }

    private InstantRequestDto view(UUID id) {
        return toDto(viewRow(id));
    }

    private InstantRequestDto toDto(RequestView v) {
        String message = switch (v.status()) {
            case "BOOKED" -> "A Genie accepted. They will arrive within about " + props.arrivalWindow().toMinutes() + " minutes.";
            case "NO_GENIE" -> (v.noGenieReason() == null ? "No Genie is free right now."
                : Reason.valueOf(v.noGenieReason()).text) + " Book a time slot instead.";
            case "CANCELLED" -> "You stopped the search.";
            default -> v.geniesAsked() == 0 ? "Looking for a Genie near you…"
                : "Asking Genie " + v.geniesAsked() + " of " + props.maxGenies() + "…";
        };
        return new InstantRequestDto(v.id(), v.status(), v.serviceId(), v.serviceName(), v.createdAt(), v.endedAt(),
            v.geniesAsked(), props.maxGenies(), v.offerExpiresAt(), v.bookingId(), message, v.noGenieReason());
    }

    private static ApiException notFound() {
        return ApiException.notFound("INSTANT_REQUEST_NOT_FOUND", "Request not found");
    }
}
