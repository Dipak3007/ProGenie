package com.progenie.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.progenie.booking.domain.Booking;
import com.progenie.pricing.PriceCalculator;
import com.progenie.booking.domain.BookingRepository;
import com.progenie.booking.domain.BookingStatus;
import com.progenie.provider.GenieDirectory;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Free redos under the 7-day warranty (complaint resolution). The redo is a normal booking request for the
 * same address and service with a zero price and payment WAIVED; by default the same Genie does it unpaid.
 * If an admin assigns a different Genie, ProGenie pays them from goodwill when the job is completed.
 */
@Service
public class BookingReworks {

    private final BookingRepository bookings;
    private final BookingSupport support;
    private final SlotService slots;
    private final GenieDirectory directory;
    private final JdbcClient jdbc;
    private final AppProperties.Booking rules;
    private final ZoneId zone;
    private final Clock clock;
    private final BookingItems items;

    public BookingReworks(BookingRepository bookings, BookingSupport support, SlotService slots, GenieDirectory directory,
                          JdbcClient jdbc, AppProperties props, Clock clock, BookingItems items) {
        this.items = items;
        this.bookings = bookings;
        this.support = support;
        this.slots = slots;
        this.directory = directory;
        this.jdbc = jdbc;
        this.rules = props.booking();
        this.zone = ZoneId.of(props.timezone());
        this.clock = clock;
    }

    /** Creates the redo request and returns its id. {@code genieId} null = the original Genie. */
    @Transactional
    public UUID create(UUID originalId, UUID genieId, Instant slotStart, UUID adminId) {
        Booking original = support.lock(originalId);
        if (original.getStatus() != BookingStatus.COMPLETED) {
            throw ApiException.unprocessable("REDO_NOT_POSSIBLE", "Only a completed job can be redone");
        }
        if (slotStart == null) {
            throw ApiException.badRequest("SLOT_REQUIRED", "Pick a time for the redo");
        }
        UUID genie = genieId == null ? original.getGenieId() : genieId;
        directory.bookableOffer(genie, original.getServiceId());
        // the redo covers every service on the original booking (Phase 3.5C), at no charge
        int minutes = Booking.KIND_INSPECTION.equals(original.getKind()) ? PriceCalculator.INSPECTION_MINUTES
            : original.getQuoteId() != null
            // a quoted job (Phase 3.5D) takes the Genie's estimate, kept as its slot length
            ? (int) Duration.between(original.getSlotStart(), original.getSlotEnd()).toMinutes()
            : items.list(originalId).stream().mapToInt(BookingItems.Item::minutes).sum();
        Instant now = clock.instant();
        Instant end = slotStart.plus(Duration.ofMinutes(minutes));
        slots.assertBookable(genie, slotStart, minutes, null);
        long n = jdbc.sql(QueryUtil.get(QueryConstants.GET_BOOKING_NEXT_REF)).query(Long.class).single();
        String ref = "PG-%d-%06d".formatted(LocalDate.ofInstant(now, zone).getYear(), n);
        Booking redo = Booking.rework(original, ref, genie, slotStart, end,
            BookingPolicy.requestExpiry(now, slotStart, rules.requestTtl()),
            "Free redo of " + original.getBookingRef() + " under the 7-day warranty");
        try {
            redo = bookings.saveAndFlush(redo);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("ex_bookings_no_overlap")) {
                throw ApiException.conflict("SLOT_TAKEN", "That slot is taken; pick another time for the redo");
            }
            throw e;
        }
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_BOOKING_COPY_ADDRESS_FROM_ORIGINAL))
            .param("redo", redo.getId()).param("original", original.getId()).update();
        items.copyFree(original.getId(), redo.getId());
        support.transitioned(redo, null, adminId, "ADMIN", BookingEvent.Type.REQUESTED, null, false);
        return redo.getId();
    }
}
