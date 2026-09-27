package com.progenie.booking;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import com.progenie.booking.BookingDtos.BookingDetailDto;
import com.progenie.booking.BookingDtos.CreateBookingRequest;
import com.progenie.booking.BookingDtos.StartCodeDto;
import com.progenie.booking.BookingQueryService.Viewer;
import com.progenie.booking.domain.Booking;
import com.progenie.booking.domain.BookingRepository;
import com.progenie.booking.domain.BookingStatus;
import com.progenie.customer.AddressDtos.AddressDto;
import com.progenie.customer.AddressService;
import com.progenie.pricing.PriceCalculator.PriceBreakdown;
import com.progenie.pricing.PricingService;
import com.progenie.provider.GenieDirectory;
import com.progenie.provider.GenieDirectory.Offer;
import com.progenie.identity.AccountGuards;
import com.progenie.legal.ConsentService;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Customer-side booking operations: book, cancel (with the 2-hour rule), reschedule, change tip,
 * get the start code. Also the admin cancellation. Genie-side operations are in {@link GenieJobService}.
 */
@Service
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final String OVERLAP_CONSTRAINT = "ex_bookings_no_overlap";

    private final BookingRepository bookings;
    private final BookingSupport support;
    private final BookingQueryService queries;
    private final SlotService slots;
    private final GenieDirectory directory;
    private final AddressService addresses;
    private final PricingService pricing;
    private final JdbcClient jdbc;
    private final AppProperties.Booking rules;
    private final ZoneId zone;
    private final Clock clock;

    private final AccountGuards guards;
    private final ConsentService consents;

    public BookingService(BookingRepository bookings, BookingSupport support, BookingQueryService queries,
                          SlotService slots, GenieDirectory directory, AddressService addresses, PricingService pricing,
                          JdbcClient jdbc, AppProperties props, Clock clock, AccountGuards guards,
                          ConsentService consents) {
        this.guards = guards;
        this.consents = consents;
        this.bookings = bookings;
        this.support = support;
        this.queries = queries;
        this.slots = slots;
        this.directory = directory;
        this.addresses = addresses;
        this.pricing = pricing;
        this.jdbc = jdbc;
        this.rules = props.booking();
        this.zone = ZoneId.of(props.timezone());
        this.clock = clock;
    }

    /**
     * Creates a booking request. Safe to retry with the same Idempotency-Key header: the first
     * booking is returned instead of a duplicate. Double booking of a Genie is prevented by the
     * database exclusion constraint even across API replicas.
     */
    @Transactional
    public BookingDetailDto create(UUID customerId, CreateBookingRequest req, String idempotencyKey) {
        String key = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : null;
        if (key != null && key.length() > 64) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key can be at most 64 characters");
        }
        if (key != null) {
            Optional<Booking> existing = bookings.findByCustomerIdAndIdempotencyKey(customerId, key);
            if (existing.isPresent()) {
                return queries.detail(existing.get().getId(), Viewer.CUSTOMER, customerId);
            }
        }
        guards.requireVerifiedPhone(customerId);
        consents.requireNoPending(customerId, "CUSTOMER");
        if (queries.hasOutstandingFee(customerId)) {
            throw ApiException.unprocessable("OUTSTANDING_FEE", "Please pay your pending cancellation fee before booking again");
        }

        Offer offer = directory.bookableOffer(req.genieId(), req.serviceId());
        AddressDto address = addresses.getOwned(customerId, req.addressId());
        if (!directory.servesLocation(offer.genieId(), address.lat(), address.lng())) {
            throw ApiException.unprocessable("OUT_OF_SERVICE_AREA", "This Genie does not travel to that address");
        }
        Instant start = req.slotStart().toInstant();
        Instant end = start.plus(Duration.ofMinutes(offer.durationMinutes()));
        slots.assertBookable(offer.genieId(), start, offer.durationMinutes(), null);

        BigDecimal tip = req.tipAmount() == null ? BigDecimal.ZERO : req.tipAmount();
        PriceBreakdown quote = pricing.estimate(offer.genieId(), offer.serviceId(), address.lat(), address.lng(), tip);
        Booking.Price price = new Booking.Price(quote.serviceAmount(), quote.distanceKm(), quote.travelFee(),
            quote.tipAmount(), quote.commissionRate(), quote.commissionAmount());

        Instant now = clock.instant();
        Booking booking = Booking.request(nextBookingRef(now), customerId, offer.genieId(), offer.serviceId(),
            address.cityId(), address.id(), start, end, BookingPolicy.requestExpiry(now, start, rules.requestTtl()),
            price, req.paymentMethod(), StringUtils.hasText(req.notes()) ? req.notes().trim() : null, key);
        try {
            booking = bookings.saveAndFlush(booking);
        } catch (DataIntegrityViolationException e) {
            String message = String.valueOf(e.getMostSpecificCause().getMessage());
            if (message.contains(OVERLAP_CONSTRAINT)) {
                throw ApiException.conflict("SLOT_TAKEN", "That slot was just taken, please pick another one");
            }
            throw e;
        }
        // Freeze the address as it is now (the customer may edit or delete it later).
        jdbc.sql("""
                UPDATE bookings b
                   SET address_snapshot = jsonb_build_object(
                           'label', a.label, 'line1', a.line1, 'line2', a.line2, 'landmark', a.landmark,
                           'area', a.area, 'city', c.name, 'pincode', a.pincode),
                       customer_location = a.location
                  FROM addresses a JOIN cities c ON c.id = a.city_id
                 WHERE b.id = :bookingId AND a.id = :addressId
                """)
            .param("bookingId", booking.getId())
            .param("addressId", address.id())
            .update();
        support.transitioned(booking, null, customerId, "CUSTOMER", BookingEvent.Type.REQUESTED, null, false);
        log.info("Booking {} requested by {} for Genie {}", booking.getBookingRef(), customerId, offer.genieId());
        return queries.detail(booking.getId(), Viewer.CUSTOMER, customerId);
    }

    /** Free until the Genie accepts and until 2 h before the slot; later cancellations owe the late fee. */
    @Transactional
    public BookingDetailDto cancel(UUID customerId, UUID bookingId, String reason) {
        Booking b = support.lockForCustomer(bookingId, customerId);
        BookingStatus from = b.getStatus();
        Instant now = clock.instant();
        BigDecimal fee = BookingPolicy.customerCancellationFee(from, b.getSlotStart(), now,
            rules.freeCancellationWindow(), rules.lateCancellationFee());
        b.cancel(now, customerId, trim(reason, "Cancelled by customer"), fee);
        support.transitioned(b, from, customerId, "CUSTOMER", BookingEvent.Type.CANCELLED, trim(reason, null), false);
        return queries.detail(bookingId, Viewer.CUSTOMER, customerId);
    }

    @Transactional
    public BookingDetailDto reschedule(UUID customerId, UUID bookingId, Instant newStart) {
        Booking b = support.lockForCustomer(bookingId, customerId);
        Instant now = clock.instant();
        if (!BookingPolicy.canReschedule(b.getStatus(), b.getSlotStart(), now, rules.freeCancellationWindow(),
            b.getRescheduledCount(), rules.maxReschedules())) {
            throw ApiException.unprocessable("CANNOT_RESCHEDULE",
                "Bookings can be moved up to " + rules.maxReschedules() + " times, until "
                    + rules.freeCancellationWindow().toHours() + " hours before the slot");
        }
        int duration = (int) Duration.between(b.getSlotStart(), b.getSlotEnd()).toMinutes();
        slots.assertBookable(b.getGenieId(), newStart, duration, b.getId());
        BookingStatus from = b.getStatus();
        b.reschedule(newStart, newStart.plus(Duration.ofMinutes(duration)),
            BookingPolicy.requestExpiry(now, newStart, rules.requestTtl()));
        try {
            support.transitioned(b, from, customerId, "CUSTOMER", BookingEvent.Type.RESCHEDULED,
                "Moved to " + newStart.atZone(zone).toLocalDateTime(), false);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(OVERLAP_CONSTRAINT)) {
                throw ApiException.conflict("SLOT_TAKEN", "That slot was just taken, please pick another one");
            }
            throw e;
        }
        return queries.detail(bookingId, Viewer.CUSTOMER, customerId);
    }

    @Transactional
    public BookingDetailDto changeTip(UUID customerId, UUID bookingId, BigDecimal tip) {
        Booking b = support.lockForCustomer(bookingId, customerId);
        b.changeTip(tip);
        support.flush();
        return queries.detail(bookingId, Viewer.CUSTOMER, customerId);
    }

    /** A fresh 4-digit code the customer tells the Genie at the door. Only its hash is stored. */
    @Transactional
    public StartCodeDto startCode(UUID customerId, UUID bookingId) {
        Booking b = support.lockForCustomer(bookingId, customerId);
        String code = BookingSupport.newStartCode();
        b.issueStartCode(BookingSupport.hashStartCode(b.getId(), code));
        return new StartCodeDto(code, "Share this code with your Genie only when they arrive. A new code replaces the old one.");
    }

    /** Admin cancellation (e.g. customer called support). The fee can be waived. */
    @Transactional
    public BookingDetailDto adminCancel(UUID adminId, UUID bookingId, String reason, boolean waiveFee) {
        Booking b = support.lock(bookingId);
        BookingStatus from = b.getStatus();
        BigDecimal fee = waiveFee ? BigDecimal.ZERO : BookingPolicy.customerCancellationFee(from, b.getSlotStart(),
            clock.instant(), rules.freeCancellationWindow(), rules.lateCancellationFee());
        b.cancel(clock.instant(), adminId, trim(reason, "Cancelled by support"), fee);
        support.transitioned(b, from, adminId, "ADMIN", BookingEvent.Type.CANCELLED, trim(reason, null), false);
        return queries.detail(bookingId, Viewer.ADMIN, adminId);
    }

    @Transactional
    public BookingDetailDto waiveFee(UUID adminId, UUID bookingId) {
        Booking b = support.lock(bookingId);
        b.waiveCancellationFee();
        support.flush();
        return queries.detail(bookingId, Viewer.ADMIN, adminId);
    }

    /** PG-2026-000123 (year in the business time zone, number from a database sequence). */
    private String nextBookingRef(Instant now) {
        long n = jdbc.sql("SELECT nextval('booking_ref_seq')").query(Long.class).single();
        return "PG-%d-%06d".formatted(LocalDate.ofInstant(now, zone).getYear(), n);
    }

    private static String trim(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
