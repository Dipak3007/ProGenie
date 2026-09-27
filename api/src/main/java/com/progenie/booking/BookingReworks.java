package com.progenie.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.progenie.booking.domain.Booking;
import com.progenie.booking.domain.BookingRepository;
import com.progenie.booking.domain.BookingStatus;
import com.progenie.provider.GenieDirectory;
import com.progenie.provider.GenieDirectory.Offer;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.error.ApiException;
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

    public BookingReworks(BookingRepository bookings, BookingSupport support, SlotService slots, GenieDirectory directory,
                          JdbcClient jdbc, AppProperties props, Clock clock) {
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
        Offer offer = directory.bookableOffer(genie, original.getServiceId());
        Instant now = clock.instant();
        Instant end = slotStart.plus(Duration.ofMinutes(offer.durationMinutes()));
        slots.assertBookable(genie, slotStart, offer.durationMinutes(), null);
        long n = jdbc.sql("SELECT nextval('booking_ref_seq')").query(Long.class).single();
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
        jdbc.sql("""
                UPDATE bookings r SET address_snapshot = o.address_snapshot, customer_location = o.customer_location
                  FROM bookings o WHERE r.id = :redo AND o.id = :original
                """)
            .param("redo", redo.getId()).param("original", original.getId()).update();
        support.transitioned(redo, null, adminId, "ADMIN", BookingEvent.Type.REQUESTED, null, false);
        return redo.getId();
    }
}
