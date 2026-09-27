package com.progenie.booking;

import java.math.BigDecimal;
import java.util.UUID;

import com.progenie.booking.domain.Booking;
import com.progenie.booking.domain.BookingRepository;
import com.progenie.shared.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The booking module's API for the payment module: what is payable, and marking it paid.
 * Keeps payment code away from the booking entity and repository.
 */
@Service
public class BookingPayments {

    /** Money snapshot of a booking, as needed for payments and ledger postings. */
    public record Payable(UUID bookingId, String bookingRef, UUID customerId, UUID genieId, String status,
                          String paymentMethod, String paymentStatus, BigDecimal serviceAmount, BigDecimal extraAmount,
                          BigDecimal travelFee, BigDecimal tipAmount, BigDecimal commissionAmount,
                          BigDecimal totalAmount, BigDecimal geniePayout, BigDecimal cancellationFee,
                          String cancellationFeeStatus) {
    }

    private final BookingRepository bookings;

    public BookingPayments(BookingRepository bookings) {
        this.bookings = bookings;
    }

    /** Loads with a row lock so a payment and a tip change cannot interleave. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payable lockPayable(UUID bookingId) {
        return toPayable(load(bookingId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markPaid(UUID bookingId) {
        load(bookingId).markPaid();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markRefunded(UUID bookingId, boolean full) {
        load(bookingId).markRefunded(full);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markCancellationFeePaid(UUID bookingId) {
        load(bookingId).markCancellationFeePaid();
    }

    private Booking load(UUID bookingId) {
        return bookings.findForUpdate(bookingId)
            .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking not found"));
    }

    private static Payable toPayable(Booking b) {
        return new Payable(b.getId(), b.getBookingRef(), b.getCustomerId(), b.getGenieId(), b.getStatus().name(),
            b.getPaymentMethod().name(), b.getPaymentStatus().name(), b.getServiceAmount(), b.getExtraAmount(),
            b.getTravelFee(), b.getTipAmount(), b.getCommissionAmount(), b.getTotalAmount(), b.getGeniePayout(),
            b.getCancellationFee(), b.getCancellationFeeStatus() == null ? null : b.getCancellationFeeStatus().name());
    }
}
