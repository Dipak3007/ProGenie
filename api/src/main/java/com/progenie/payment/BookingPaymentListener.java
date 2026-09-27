package com.progenie.payment;

import com.progenie.booking.BookingEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * When a Genie completes a cash job and confirms the cash, record the payment and ledger entries
 * inside the same transaction as the completion (both succeed or both fail).
 */
@Component
class BookingPaymentListener {

    private final PaymentService payments;

    BookingPaymentListener(PaymentService payments) {
        this.payments = payments;
    }

    @EventListener(condition = "#event.type() == T(com.progenie.booking.BookingEvent$Type).COMPLETED and #event.cashCollected()")
    void onCashJobCompleted(BookingEvent event) {
        payments.recordCash(event.bookingId());
    }

    /** A free redo done by a different Genie is paid by ProGenie (goodwill). */
    @EventListener(condition = "#event.type() == T(com.progenie.booking.BookingEvent$Type).COMPLETED")
    void onJobCompleted(BookingEvent event) {
        payments.recordReworkPayout(event.bookingId());
    }
}
