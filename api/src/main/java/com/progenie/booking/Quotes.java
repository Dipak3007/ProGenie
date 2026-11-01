package com.progenie.booking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.progenie.booking.BookingDtos.AcceptQuoteRequest;
import com.progenie.booking.BookingDtos.BookingDetailDto;
import com.progenie.booking.BookingDtos.QuoteLineRequest;
import com.progenie.booking.BookingDtos.QuoteRequest;
import com.progenie.booking.BookingQueryService.Viewer;
import com.progenie.booking.domain.Booking;
import com.progenie.booking.domain.BookingRepository;
import com.progenie.booking.domain.BookingStatus;
import com.progenie.identity.AccountGuards;
import com.progenie.legal.ConsentService;
import com.progenie.pricing.PricingService;
import com.progenie.pricing.PricingService.Travel;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Inspection visit + quote (Phase 3.5D). After an inspection the Genie sends a priced quote: catalog services and
 * custom lines (commission on every line), an estimated job length, valid 7 days. They can revise it until the
 * customer answers (only the latest version is kept). The customer accepts by picking a time, which books the job
 * already accepted, with the quote's lines and the inspection fee taken off; or declines. Unanswered quotes expire.
 */
@Service
public class Quotes {

    private static final Logger log = LoggerFactory.getLogger(Quotes.class);
    /** How long a quote (its latest version) stays open. */
    static final Duration VALID_FOR = Duration.ofDays(7);
    /** How long after the inspection the Genie can still send a quote. */
    static final Duration SEND_WINDOW = Duration.ofDays(7);

    /** A stored quote. */
    record Quote(UUID id, UUID inspectionBookingId, String inspectionBookingRef, UUID genieId, UUID customerId,
                 String status, int version, String note, int durationMinutes, BigDecimal totalAmount,
                 OffsetDateTime validUntil, String declineReason, OffsetDateTime respondedAt, UUID jobBookingId,
                 String jobBookingRef, BigDecimal inspectionFeePaid, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    record Line(long id, int position, Long serviceId, String description, int quantity, BigDecimal unitPrice,
                BigDecimal amount) {
    }

    record OfferedService(long serviceId, String name, int durationMinutes) {
    }

    /** What the inspection booking says (for sending and accepting). */
    record Inspection(UUID id, String bookingRef, String kind, String status, UUID genieId, UUID customerId,
                      OffsetDateTime completedAt, long cityId, long serviceId, UUID addressId, Double lat, Double lng,
                      BigDecimal serviceAmount) {
    }

    private final JdbcClient jdbc;
    private final BookingRepository bookings;
    private final BookingSupport support;
    private final BookingQueryService queries;
    private final BookingItems items;
    private final SlotService slots;
    private final PricingService pricing;
    private final AccountGuards guards;
    private final ConsentService consents;
    private final ApplicationEventPublisher events;
    private final JobLock lock;
    private final QuoteReader reader;
    private final AppProperties.Booking rules;
    private final ZoneId zone;
    private final Clock clock;

    public Quotes(JdbcClient jdbc, BookingRepository bookings, BookingSupport support, BookingQueryService queries,
                  BookingItems items, SlotService slots, PricingService pricing, AccountGuards guards,
                  ConsentService consents, ApplicationEventPublisher events, JobLock lock, QuoteReader reader,
                  AppProperties props, Clock clock) {
        this.reader = reader;
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.support = support;
        this.queries = queries;
        this.items = items;
        this.slots = slots;
        this.pricing = pricing;
        this.guards = guards;
        this.consents = consents;
        this.events = events;
        this.lock = lock;
        this.rules = props.booking();
        this.zone = ZoneId.of(props.timezone());
        this.clock = clock;
    }

    // ------------------------------------------------------------------ the Genie

    /**
     * Sends (or revises) the quote for a completed inspection. 422 NOT_AN_INSPECTION, INSPECTION_NOT_DONE,
     * QUOTE_WINDOW_CLOSED; 409 QUOTE_CLOSED (already answered or expired); 400 INVALID_QUOTE_LINE, DUPLICATE_SERVICE;
     * 422 SERVICE_NOT_OFFERED.
     */
    @Transactional
    public BookingDetailDto send(UUID genieId, UUID inspectionId, QuoteRequest req) {
        Inspection i = inspection(inspectionId).filter(x -> x.genieId().equals(genieId))
            .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking not found"));
        if (!Booking.KIND_INSPECTION.equals(i.kind())) {
            throw ApiException.unprocessable("NOT_AN_INSPECTION", "Quotes are sent after an inspection visit");
        }
        if (!BookingStatus.COMPLETED.name().equals(i.status()) || i.completedAt() == null) {
            throw ApiException.unprocessable("INSPECTION_NOT_DONE", "Complete the inspection visit first, then send the quote");
        }
        Instant now = clock.instant();
        if (now.isAfter(i.completedAt().toInstant().plus(SEND_WINDOW))) {
            throw ApiException.unprocessable("QUOTE_WINDOW_CLOSED", "Quotes can be sent up to 7 days after the inspection");
        }
        List<Line> lines = lines(genieId, req.lines());
        BigDecimal total = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String note = StringUtils.hasText(req.note()) ? req.note().trim() : null;
        OffsetDateTime validUntil = Times.odt(now.plus(VALID_FOR));
        Optional<Quote> existing = lockByInspection(inspectionId);
        UUID quoteId;
        int version;
        if (existing.isEmpty()) {
            quoteId = jdbc.sql(QueryUtil.get(QueryConstants.INSERT_QUOTE))
                .param("inspection", inspectionId).param("genie", genieId).param("customer", i.customerId())
                .param("note", note).param("minutes", req.durationMinutes()).param("total", total)
                .param("validUntil", validUntil).query(UUID.class).single();
            version = 1;
        } else {
            Quote q = effective(existing.get(), now);
            if (!"SENT".equals(q.status())) {
                throw ApiException.conflict("QUOTE_CLOSED", "This quote was already " + q.status().toLowerCase() + ". It can't be changed");
            }
            quoteId = q.id();
            version = q.version() + 1;
            jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_QUOTE_REVISE))
                .param("id", quoteId).param("note", note).param("minutes", req.durationMinutes()).param("total", total)
                .param("validUntil", validUntil).update();
            jdbc.sql(QueryUtil.get(QueryConstants.DELETE_QUOTE_LINES)).param("id", quoteId).update();
        }
        for (Line l : lines) {
            jdbc.sql(QueryUtil.get(QueryConstants.INSERT_QUOTE_LINE))
                .param("quote", quoteId).param("position", l.position()).param("service", l.serviceId())
                .param("description", l.description()).param("quantity", l.quantity()).param("unitPrice", l.unitPrice())
                .param("amount", l.amount()).update();
        }
        events.publishEvent(new QuoteEvent(QuoteEvent.Type.SENT, quoteId, inspectionId, i.bookingRef(), i.customerId(),
            genieId, version, total, null, null));
        log.info("Quote v{} for inspection {} sent by Genie {} ({} lines, total {})", version, i.bookingRef(), genieId,
            lines.size(), total);
        return queries.detail(inspectionId, Viewer.GENIE, genieId);
    }

    /** Checks and prices the lines: the Genie's own services (once each) or custom lines with a description. */
    private List<Line> lines(UUID genieId, List<QuoteLineRequest> requested) {
        Set<Long> ids = new HashSet<>();
        requested.forEach(l -> {
            if (l.serviceId() != null && !ids.add(l.serviceId())) {
                throw ApiException.badRequest("DUPLICATE_SERVICE", "Each service can be in a quote once (change its quantity instead)");
            }
        });
        Map<Long, OfferedService> offered = ids.isEmpty() ? Map.of() : jdbc.sql(QueryUtil.get(QueryConstants.GET_QUOTE_OFFERED_SERVICES))
            .param("genieId", genieId).param("ids", List.copyOf(ids))
            .query(OfferedService.class).list().stream()
            .collect(Collectors.toMap(OfferedService::serviceId, Function.identity()));
        List<Line> out = new ArrayList<>();
        int position = 1;
        for (QuoteLineRequest r : requested) {
            String description = StringUtils.hasText(r.description()) ? r.description().trim() : null;
            if (r.serviceId() != null) {
                OfferedService s = offered.get(r.serviceId());
                if (s == null) {
                    throw ApiException.unprocessable("SERVICE_NOT_OFFERED", "You don't offer one of those services");
                }
                description = description == null ? s.name() : description;
            } else if (description == null) {
                throw ApiException.badRequest("INVALID_QUOTE_LINE", "Describe each custom line (e.g. \"Replace 20 m of wiring\")");
            }
            BigDecimal unit = r.unitPrice().setScale(2, RoundingMode.HALF_UP);
            BigDecimal amount = unit.multiply(BigDecimal.valueOf(r.quantity())).setScale(2, RoundingMode.HALF_UP);
            out.add(new Line(0, position++, r.serviceId(), description, r.quantity(), unit, amount));
        }
        return out;
    }

    // ------------------------------------------------------------------ the customer

    /**
     * Accepts the quote: books the job at {@code slotStart} (already accepted, since the Genie quoted it) with the
     * quote's lines, the inspection fee taken off and the travel fee to the same address. 409 QUOTE_CLOSED; 422
     * QUOTE_EXPIRED and the usual slot errors.
     */
    @Transactional
    public BookingDetailDto accept(UUID customerId, UUID quoteId, AcceptQuoteRequest req) {
        Quote q = lockById(quoteId).filter(x -> x.customerId().equals(customerId))
            .orElseThrow(() -> ApiException.notFound("QUOTE_NOT_FOUND", "Quote not found"));
        Instant now = clock.instant();
        requireOpen(q, now);
        guards.requireVerifiedPhone(customerId);
        consents.requireNoPending(customerId, "CUSTOMER");
        if (queries.hasOutstandingFee(customerId)) {
            throw ApiException.unprocessable("OUTSTANDING_FEE", "Please pay your pending cancellation fee before booking again");
        }
        Inspection i = inspection(q.inspectionBookingId()).orElseThrow();
        if (i.lat() == null || i.lng() == null) {
            throw ApiException.unprocessable("ADDRESS_MISSING", "The inspection's address has no map pin");
        }
        List<Line> lines = lines(quoteId);
        Instant start = req.slotStart().toInstant();
        slots.assertBookable(q.genieId(), start, q.durationMinutes(), null);

        Travel travel = pricing.travel(q.genieId(), i.lat(), i.lng());
        BigDecimal total = q.totalAmount();
        BigDecimal credit = q.inspectionFeePaid().min(total).setScale(2, RoundingMode.HALF_UP);
        List<BigDecimal> commissions = lines.stream()
            .map(l -> l.amount().multiply(travel.commissionRate()).setScale(2, RoundingMode.HALF_UP)).toList();
        BigDecimal commission = commissions.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tip = (req.tipAmount() == null ? BigDecimal.ZERO : req.tipAmount()).setScale(2, RoundingMode.HALF_UP);
        Booking.Price price = new Booking.Price(total.subtract(credit), travel.distanceKm(), travel.travelFee(), tip,
            travel.commissionRate(), commission);
        long firstService = lines.stream().map(Line::serviceId).filter(java.util.Objects::nonNull).findFirst()
            .orElse(i.serviceId());
        String label = BookingItems.label(lines.stream().map(Line::description).toList(),
            lines.stream().map(Line::quantity).toList());
        Booking job = Booking.request(nextBookingRef(now), customerId, q.genieId(), firstService, label, i.cityId(),
            i.addressId(), start, start.plus(Duration.ofMinutes(q.durationMinutes())),
            BookingPolicy.requestExpiry(now, start, rules.requestTtl()), price, req.paymentMethod(),
            StringUtils.hasText(req.notes()) ? req.notes().trim() : null, null);
        job.markFromQuote(quoteId, credit);
        job.accept(now);
        try {
            job = bookings.saveAndFlush(job);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("ex_bookings_no_overlap")) {
                throw ApiException.conflict("SLOT_TAKEN", "That slot was just taken, please pick another one");
            }
            throw e;
        }
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_BOOKING_COPY_ADDRESS_FROM_ORIGINAL))
            .param("redo", job.getId()).param("original", i.id()).update();
        Map<Long, Integer> minutes = jdbc.sql(QueryUtil.get(QueryConstants.GET_QUOTE_LINE_SERVICE_MINUTES))
            .param("id", quoteId).query((rs, n) -> Map.entry(rs.getLong(1), rs.getInt(2))).list().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        for (int n = 0; n < lines.size(); n++) {
            Line l = lines.get(n);
            items.insertQuoted(job.getId(), n + 1, l.serviceId(), l.description(), l.quantity(), l.unitPrice(), l.amount(),
                commissions.get(n), l.serviceId() == null ? 0 : minutes.getOrDefault(l.serviceId(), 0));
        }
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_QUOTE_ACCEPTED)).param("id", quoteId).param("job", job.getId()).update();
        support.transitioned(job, null, customerId, "CUSTOMER", BookingEvent.Type.ACCEPTED, "Quote accepted", false);
        events.publishEvent(new QuoteEvent(QuoteEvent.Type.ACCEPTED, quoteId, i.id(), job.getBookingRef(), customerId,
            q.genieId(), q.version(), total, job.getSlotStart(), job.getId()));
        log.info("Quote for inspection {} accepted: job {} booked by {}", i.bookingRef(), job.getBookingRef(), customerId);
        return queries.detail(job.getId(), Viewer.CUSTOMER, customerId);
    }

    @Transactional
    public BookingDetailDto decline(UUID customerId, UUID quoteId, String reason) {
        Quote q = lockById(quoteId).filter(x -> x.customerId().equals(customerId))
            .orElseThrow(() -> ApiException.notFound("QUOTE_NOT_FOUND", "Quote not found"));
        requireOpen(q, clock.instant());
        String why = StringUtils.hasText(reason) ? reason.trim() : null;
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_QUOTE_DECLINED)).param("id", quoteId).param("reason", why).update();
        events.publishEvent(new QuoteEvent(QuoteEvent.Type.DECLINED, quoteId, q.inspectionBookingId(), q.inspectionBookingRef(),
            customerId, q.genieId(), q.version(), q.totalAmount(), null, null));
        log.info("Quote for inspection {} declined by {}", q.inspectionBookingRef(), customerId);
        return queries.detail(q.inspectionBookingId(), Viewer.CUSTOMER, customerId);
    }

    /** Minutes the quoted job takes, for its free slots; the Genie must be the quote's. */
    @Transactional(readOnly = true)
    public int durationMinutes(UUID quoteId, UUID genieId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_QUOTE_DURATION)).param("id", quoteId).param("genieId", genieId)
            .query(Integer.class).optional()
            .orElseThrow(() -> ApiException.notFound("QUOTE_NOT_FOUND", "Quote not found"));
    }

    // ------------------------------------------------------------------ expiry

    /** Hourly: quotes nobody answered within 7 days of their latest version are closed. */
    @Scheduled(cron = "0 17 * * * *")
    @Transactional
    public void expireOld() {
        if (!lock.tryAcquire("quotes-expiry")) {
            return;
        }
        int n = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_QUOTE_EXPIRED)).param("now", Times.odt(clock.instant())).update();
        if (n > 0) {
            log.info("{} unanswered quote(s) expired", n);
        }
    }

    // ------------------------------------------------------------------ helpers

    private void requireOpen(Quote q, Instant now) {
        Quote e = effective(q, now);
        if ("EXPIRED".equals(e.status())) {
            throw ApiException.unprocessable("QUOTE_EXPIRED", "This quote has expired. Ask the Genie for a new one");
        }
        if (!"SENT".equals(e.status())) {
            throw ApiException.conflict("QUOTE_CLOSED", "This quote was already " + e.status().toLowerCase());
        }
    }

    /** SENT past its date reads as EXPIRED even before the hourly job stores it. */
    static Quote effective(Quote q, Instant now) {
        if ("SENT".equals(q.status()) && !now.isBefore(q.validUntil().toInstant())) {
            return new Quote(q.id(), q.inspectionBookingId(), q.inspectionBookingRef(), q.genieId(), q.customerId(), "EXPIRED",
                q.version(), q.note(), q.durationMinutes(), q.totalAmount(), q.validUntil(), q.declineReason(),
                q.respondedAt(), q.jobBookingId(), q.jobBookingRef(), q.inspectionFeePaid(), q.createdAt(), q.updatedAt());
        }
        return q;
    }

    private Optional<Inspection> inspection(UUID id) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_QUOTE_INSPECTION)).param("id", id).query(Inspection.class).optional();
    }

    private Optional<Quote> lockByInspection(UUID inspectionId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.LOCK_QUOTE_BY_INSPECTION)).param("id", inspectionId).query(Quote.class).optional();
    }

    private Optional<Quote> lockById(UUID id) {
        return jdbc.sql(QueryUtil.get(QueryConstants.LOCK_QUOTE_BY_ID)).param("id", id).query(Quote.class).optional();
    }

    private List<Line> lines(UUID quoteId) {
        return reader.lines(quoteId);
    }

    private String nextBookingRef(Instant now) {
        long n = jdbc.sql(QueryUtil.get(QueryConstants.GET_BOOKING_NEXT_REF)).query(Long.class).single();
        return "PG-%d-%06d".formatted(LocalDate.ofInstant(now, zone).getYear(), n);
    }
}
