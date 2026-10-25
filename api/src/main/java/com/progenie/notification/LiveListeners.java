package com.progenie.notification;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.progenie.booking.BookingEvent;
import com.progenie.messaging.LiveHub;
import com.progenie.payment.PaymentDtos.PaymentReceived;
import com.progenie.payment.PaymentDtos.RefundProcessed;
import com.progenie.support.TicketEvents.TicketRaised;
import com.progenie.support.TicketEvents.TicketReplied;
import com.progenie.support.TicketEvents.TicketResolved;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns domain events into live pushes, after the transaction commits (so the browser never reloads data that
 * was rolled back). The payload only says what changed; the page fetches the details itself.
 */
@Component
class LiveListeners {

    private final LiveHub hub;

    LiveListeners(LiveHub hub) {
        this.hub = hub;
    }

    @TransactionalEventListener
    public void onBooking(BookingEvent e) {
        booking(e.bookingId(), e.type().name(), e.customerId(), e.genieId());
    }

    @TransactionalEventListener
    public void onPayment(PaymentReceived e) {
        booking(e.bookingId(), "PAID", e.customerId(), e.genieId());
    }

    @TransactionalEventListener
    public void onRefund(RefundProcessed e) {
        booking(e.bookingId(), "REFUNDED", e.customerId(), e.genieId());
    }

    @TransactionalEventListener
    public void onTicketRaised(TicketRaised e) {
        ticket(e.ticketId(), e.bookingId(), "RAISED", e.customerId(), e.genieId(), e.raisedBy());
    }

    @TransactionalEventListener
    public void onTicketReplied(TicketReplied e) {
        ticket(e.ticketId(), e.bookingId(), "REPLIED", e.customerId(), e.genieId(), e.raisedBy());
    }

    @TransactionalEventListener
    public void onTicketResolved(TicketResolved e) {
        ticket(e.ticketId(), e.bookingId(), "RESOLVED", e.customerId(), e.genieId(), e.raisedBy());
    }

    private void booking(UUID bookingId, String change, UUID customerId, UUID genieId) {
        Map<String, Object> data = Map.of("bookingId", bookingId, "change", change);
        hub.toUser(customerId, "booking", data);
        hub.toUser(genieId, "booking", data);
        hub.toRole("ADMIN", "booking", data);
    }

    private void ticket(UUID ticketId, UUID bookingId, String change, UUID customerId, UUID genieId, UUID raisedBy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ticketId", ticketId);
        data.put("bookingId", bookingId); // null for privacy requests
        data.put("change", change);
        hub.toUser(customerId, "ticket", data);
        hub.toUser(genieId, "ticket", data);
        if (raisedBy != null && !raisedBy.equals(customerId) && !raisedBy.equals(genieId)) {
            hub.toUser(raisedBy, "ticket", data);
        }
        hub.toRole("ADMIN", "ticket", data);
    }
}
