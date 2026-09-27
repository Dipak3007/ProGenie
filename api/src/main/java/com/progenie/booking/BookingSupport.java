package com.progenie.booking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

import com.progenie.booking.domain.Booking;
import com.progenie.booking.domain.BookingRepository;
import com.progenie.booking.domain.BookingStatus;
import com.progenie.shared.error.ApiException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Shared plumbing for the booking services: locked loading, status history, events, start codes. */
@Component
class BookingSupport {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final BookingRepository bookings;
    private final JdbcClient jdbc;
    private final ApplicationEventPublisher events;

    BookingSupport(BookingRepository bookings, JdbcClient jdbc, ApplicationEventPublisher events) {
        this.bookings = bookings;
        this.jdbc = jdbc;
        this.events = events;
    }

    Booking lockForCustomer(UUID bookingId, UUID customerId) {
        return bookings.findForUpdate(bookingId)
            .filter(b -> b.getCustomerId().equals(customerId))
            .orElseThrow(BookingSupport::notFound);
    }

    Booking lockForGenie(UUID bookingId, UUID genieId) {
        return bookings.findForUpdate(bookingId)
            .filter(b -> b.getGenieId().equals(genieId))
            .orElseThrow(BookingSupport::notFound);
    }

    Booking lock(UUID bookingId) {
        return bookings.findForUpdate(bookingId).orElseThrow(BookingSupport::notFound);
    }

    /** Writes the history row and publishes the event. Call after the entity change (it is flushed first). */
    void transitioned(Booking b, BookingStatus from, UUID actorId, String actorRole, BookingEvent.Type type,
                      String reason, boolean cashCollected) {
        bookings.flush();
        jdbc.sql("""
                INSERT INTO booking_status_history (booking_id, from_status, to_status, changed_by, reason)
                VALUES (:id, :from, :to, :actor, :reason)
                """)
            .param("id", b.getId())
            .param("from", from == null ? null : from.name())
            .param("to", b.getStatus().name())
            .param("actor", actorId)
            .param("reason", reason)
            .update();
        if (type == BookingEvent.Type.RESCHEDULED) {
            // the new time gets its own reminder
            jdbc.sql("UPDATE bookings SET reminder_sent_at = NULL WHERE id = :id").param("id", b.getId()).update();
        }
        events.publishEvent(new BookingEvent(type, b.getId(), b.getBookingRef(), b.getCustomerId(), b.getGenieId(),
            b.getSlotStart(), actorRole, reason, cashCollected));
        // Same-transaction listeners (e.g. cash payment) may have changed the booking again.
        bookings.flush();
    }

    /** Pushes pending entity changes to the database so SQL read models see them. */
    void flush() {
        bookings.flush();
    }

    static String newStartCode() {
        return String.format("%04d", RANDOM.nextInt(10_000));
    }

    /** Start codes are stored hashed and salted with the booking id. */
    static String hashStartCode(UUID bookingId, String code) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest((bookingId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean startCodeMatches(Booking b, String code) {
        if (b.getStartOtpHash() == null || code == null) {
            return false;
        }
        return MessageDigest.isEqual(
            b.getStartOtpHash().getBytes(StandardCharsets.UTF_8),
            hashStartCode(b.getId(), code).getBytes(StandardCharsets.UTF_8));
    }

    private static ApiException notFound() {
        return ApiException.notFound("BOOKING_NOT_FOUND", "Booking not found");
    }
}
