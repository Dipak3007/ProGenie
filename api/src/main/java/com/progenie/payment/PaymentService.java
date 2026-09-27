package com.progenie.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.progenie.booking.BookingPayments;
import com.progenie.booking.BookingPayments.Payable;
import com.progenie.payment.PaymentDtos.PaymentDto;
import com.progenie.payment.PaymentDtos.PaymentOrderDto;
import com.progenie.payment.PaymentDtos.PaymentReceived;
import com.progenie.payment.PaymentGateway.GatewayOrder;
import com.progenie.payment.PaymentGateway.WebhookEvent;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Takes money for bookings: cash recorded by the Genie at completion, or online through the
 * gateway after the job ("pay after the job"). Also the late-cancellation fee. Every successful
 * payment posts balanced ledger entries in the same transaction.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    record PaymentRow(UUID id, UUID bookingId, UUID customerId, String purpose, String provider, String providerOrderId,
                      BigDecimal amount, String status) {
    }

    private final JdbcClient jdbc;
    private final BookingPayments bookings;
    private final LedgerService ledger;
    private final PaymentGateway gateway;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final String currency;
    private final RefundService refunds;

    public PaymentService(JdbcClient jdbc, BookingPayments bookings, LedgerService ledger, PaymentGateway gateway,
                          ApplicationEventPublisher events, JsonMapper json, AppProperties props,
                          RefundService refunds) {
        this.refunds = refunds;
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.ledger = ledger;
        this.gateway = gateway;
        this.events = events;
        this.json = json;
        this.currency = props.payments().currency();
    }

    // ------------------------------------------------------------------ online

    /** Creates (or reuses) a gateway order for what the customer owes on this booking right now. */
    @Transactional
    public PaymentOrderDto createOrder(UUID customerId, UUID bookingId) {
        Payable b = bookings.lockPayable(bookingId);
        if (!b.customerId().equals(customerId)) {
            throw ApiException.notFound("BOOKING_NOT_FOUND", "Booking not found");
        }
        String purpose;
        BigDecimal amount;
        if ("DUE".equals(b.cancellationFeeStatus())) {
            purpose = "CANCELLATION_FEE";
            amount = b.cancellationFee();
        } else if ("COMPLETED".equals(b.status()) && "UNPAID".equals(b.paymentStatus())) {
            purpose = "BOOKING";
            amount = b.totalAmount();
        } else {
            throw ApiException.unprocessable("NOTHING_TO_PAY", "Nothing is due on this booking right now");
        }

        Optional<PaymentRow> pending = jdbc.sql("""
                SELECT id, booking_id, customer_id, purpose, provider, provider_order_id, amount, status FROM payments
                 WHERE booking_id = :b AND purpose = :purpose AND status = 'PENDING' AND provider = :provider
                 ORDER BY created_at DESC LIMIT 1
                """)
            .param("b", bookingId).param("purpose", purpose).param("provider", gateway.name())
            .query(PaymentRow.class).optional();
        if (pending.isPresent() && pending.get().amount().compareTo(amount) == 0) {
            PaymentRow p = pending.get();
            return new PaymentOrderDto(p.id(), p.provider(), p.providerOrderId(), p.amount(), currency, purpose, b.bookingRef(),
                gateway.publicKey());
        }
        // The amount changed (e.g. a new tip): retire the old order.
        pending.ifPresent(p -> fail(p.id(), "Replaced by a new order"));

        GatewayOrder order = gateway.createOrder(b.bookingRef() + "/" + purpose, amount, currency);
        UUID paymentId = jdbc.sql("""
                INSERT INTO payments (booking_id, customer_id, method, provider, provider_order_id, amount, status, purpose)
                VALUES (:b, :c, 'ONLINE', :provider, :order, :amount, 'PENDING', :purpose)
                RETURNING id
                """)
            .param("b", bookingId).param("c", customerId).param("provider", gateway.name())
            .param("order", order.orderId()).param("amount", amount).param("purpose", purpose)
            .query(UUID.class).single();
        return new PaymentOrderDto(paymentId, gateway.name(), order.orderId(), amount, currency, purpose, b.bookingRef(),
            gateway.publicKey());
    }

    /** Checkout callback. The signature proves the gateway really captured this payment. */
    @Transactional(noRollbackFor = ApiException.class)
    public PaymentDto confirm(UUID customerId, UUID paymentId, String providerPaymentId, String signature) {
        PaymentRow p = lockPayment(paymentId)
            .filter(row -> customerId.equals(row.customerId()))
            .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        if ("SUCCEEDED".equals(p.status())) {
            return get(paymentId);
        }
        if (!"PENDING".equals(p.status())) {
            throw ApiException.unprocessable("PAYMENT_CLOSED", "This payment is " + p.status() + "; start a new one");
        }
        if (!gateway.verifyPaymentSignature(p.providerOrderId(), providerPaymentId, signature)) {
            fail(p.id(), "Invalid signature");
            throw ApiException.badRequest("INVALID_SIGNATURE", "Payment could not be verified");
        }
        succeed(p, providerPaymentId);
        return get(paymentId);
    }

    /** Local testing only (gateway=fake): pretend the customer completed (or failed) checkout. */
    @Transactional(noRollbackFor = ApiException.class)
    public PaymentDto simulate(UUID customerId, UUID paymentId, boolean success) {
        if (!(gateway instanceof FakePaymentGateway fake)) {
            throw ApiException.notFound("NOT_AVAILABLE", "Simulation is only available with the fake gateway");
        }
        PaymentRow p = lockPayment(paymentId)
            .filter(row -> customerId.equals(row.customerId()))
            .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        if (!success) {
            if ("PENDING".equals(p.status())) {
                fail(p.id(), "Declined by bank (simulated)");
            }
            return get(paymentId);
        }
        FakePaymentGateway.SimulatedPayment result = fake.simulateCheckout(p.providerOrderId());
        return confirm(customerId, paymentId, result.providerPaymentId(), result.signature());
    }

    /**
     * Server-to-server notification from the gateway, verified by HMAC over the raw body. Each event id is
     * recorded in webhook_events first, so a redelivered event is acknowledged and ignored.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void webhook(String provider, String rawBody, String signature, String eventIdHeader) {
        if (!gateway.name().equals(provider) || !gateway.verifyWebhookSignature(rawBody, signature)) {
            throw ApiException.unauthorized("INVALID_SIGNATURE", "Webhook signature is not valid");
        }
        WebhookEvent event = gateway.parseWebhook(rawBody, eventIdHeader);
        int fresh = jdbc.sql("INSERT INTO webhook_events (provider, event_id) VALUES (:p, :e) ON CONFLICT DO NOTHING")
            .param("p", provider).param("e", event.eventId()).update();
        if (fresh == 0) {
            log.info("Ignoring repeated webhook {} {}", provider, event.eventId());
            return;
        }
        switch (event.type()) {
            case "payment.captured", "payment.failed" -> paymentEvent(provider, event);
            case "refund.processed" -> refunds.onGatewayRefund(provider, event.refundId(), true, null);
            case "refund.failed" -> refunds.onGatewayRefund(provider, event.refundId(), false, event.reason());
            default -> log.info("Ignoring webhook event {}", event.type());
        }
    }

    private void paymentEvent(String provider, WebhookEvent event) {
        Optional<PaymentRow> payment = jdbc.sql("""
                SELECT id, booking_id, customer_id, purpose, provider, provider_order_id, amount, status FROM payments
                 WHERE provider = :provider AND provider_order_id = :order FOR UPDATE
                """)
            .param("provider", provider).param("order", event.orderId())
            .query(PaymentRow.class).optional();
        if (payment.isEmpty() || !"PENDING".equals(payment.get().status())) {
            return; // unknown or already processed: acknowledge so the gateway stops retrying
        }
        if ("payment.captured".equals(event.type())) {
            succeed(payment.get(), event.paymentId());
        } else {
            fail(payment.get().id(), event.reason() == null ? "Payment failed" : event.reason());
        }
    }

    // ------------------------------------------------------------------ cash

    /** Called when a Genie completes a cash job and confirms the cash was collected. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCash(UUID bookingId) {
        Payable b = bookings.lockPayable(bookingId);
        if (!"UNPAID".equals(b.paymentStatus())) {
            return;
        }
        UUID paymentId = jdbc.sql("""
                INSERT INTO payments (booking_id, customer_id, method, amount, status, purpose)
                VALUES (:b, :c, 'CASH', :amount, 'SUCCEEDED', 'BOOKING')
                RETURNING id
                """)
            .param("b", bookingId).param("c", b.customerId()).param("amount", b.totalAmount())
            .query(UUID.class).single();
        applyBookingPayment(paymentId, b, true);
    }

    /**
     * Completion of a free redo: if a different Genie did it, ProGenie pays them what the original Genie earned on
     * the service and travel (PLATFORM_GOODWILL D, GENIE_EARNINGS C). The original Genie's redo is unpaid.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordReworkPayout(UUID bookingId) {
        record Rework(UUID genieId, String bookingRef, UUID originalGenieId, BigDecimal share) {
        }
        jdbc.sql("""
                SELECT r.genie_id, r.booking_ref, o.genie_id AS original_genie_id,
                       o.service_amount + o.travel_fee - o.commission_amount AS share
                  FROM bookings r JOIN bookings o ON o.id = r.rework_of_id
                 WHERE r.id = :id
                """)
            .param("id", bookingId).query(Rework.class).optional()
            .filter(r -> !r.genieId().equals(r.originalGenieId()) && r.share().signum() > 0)
            .ifPresent(r -> ledger.postBalanced(List.of(
                new LedgerPostings.Entry(bookingId, null, "PLATFORM_GOODWILL", "D", r.share(),
                    "Redo by another Genie for " + r.bookingRef(), null),
                new LedgerPostings.Entry(bookingId, r.genieId(), "GENIE_EARNINGS", "C", r.share(),
                    "Redo paid by ProGenie for " + r.bookingRef(), null))));
    }

    // ------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public List<PaymentDto> forBooking(UUID customerId, UUID bookingId) {
        return jdbc.sql("""
                SELECT p.id, p.purpose, p.method, p.provider, p.amount, p.status, p.failure_reason, p.created_at,
                       (SELECT coalesce(sum(r.amount), 0) FROM refunds r WHERE r.payment_id = p.id AND r.status = 'PROCESSED') AS refunded_amount
                  FROM payments p JOIN bookings b ON b.id = p.booking_id
                 WHERE p.booking_id = :b AND b.customer_id = :c
                 ORDER BY p.created_at DESC
                """)
            .param("b", bookingId).param("c", customerId)
            .query(PaymentDto.class).list();
    }

    /** Admin: every payment on a booking, with what was refunded. */
    @Transactional(readOnly = true)
    public List<PaymentDto> forBookingAdmin(UUID bookingId) {
        return jdbc.sql("""
                SELECT p.id, p.purpose, p.method, p.provider, p.amount, p.status, p.failure_reason, p.created_at,
                       (SELECT coalesce(sum(r.amount), 0) FROM refunds r WHERE r.payment_id = p.id AND r.status = 'PROCESSED') AS refunded_amount
                  FROM payments p WHERE p.booking_id = :b ORDER BY p.created_at DESC
                """)
            .param("b", bookingId).query(PaymentDto.class).list();
    }

    private PaymentDto get(UUID paymentId) {
        return jdbc.sql("""
                SELECT p.id, p.purpose, p.method, p.provider, p.amount, p.status, p.failure_reason, p.created_at,
                       (SELECT coalesce(sum(r.amount), 0) FROM refunds r WHERE r.payment_id = p.id AND r.status = 'PROCESSED') AS refunded_amount
                  FROM payments p WHERE p.id = :id
                """)
            .param("id", paymentId).query(PaymentDto.class).single();
    }

    // ------------------------------------------------------------------ internals

    private void succeed(PaymentRow p, String providerPaymentId) {
        Payable b = bookings.lockPayable(p.bookingId());
        boolean forFee = "CANCELLATION_FEE".equals(p.purpose());
        BigDecimal due = forFee ? b.cancellationFee() : b.totalAmount();
        boolean stillDue = forFee ? "DUE".equals(b.cancellationFeeStatus()) : "UNPAID".equals(b.paymentStatus());
        if (!stillDue) {
            fail(p.id(), "Already paid");
            throw ApiException.conflict("ALREADY_PAID", "This has already been paid");
        }
        if (p.amount().compareTo(due) != 0) {
            fail(p.id(), "Amount changed");
            throw ApiException.conflict("AMOUNT_CHANGED", "The amount changed; please start the payment again");
        }
        jdbc.sql("UPDATE payments SET status = 'SUCCEEDED', provider_ref = :ref, updated_at = now() WHERE id = :id")
            .param("ref", providerPaymentId).param("id", p.id()).update();
        if (forFee) {
            ledger.postBalanced(LedgerPostings.cancellationFeePaid(b));
            bookings.markCancellationFeePaid(b.bookingId());
            events.publishEvent(new PaymentReceived(p.id(), b.bookingId(), b.bookingRef(), b.customerId(), b.genieId(),
                p.amount(), p.purpose(), "ONLINE"));
        } else {
            applyBookingPayment(p.id(), b, false);
        }
        log.info("Payment {} succeeded for {} ({})", p.id(), b.bookingRef(), p.purpose());
    }

    private void applyBookingPayment(UUID paymentId, Payable b, boolean cash) {
        ledger.postBalanced(LedgerPostings.bookingPaid(b, cash));
        bookings.markPaid(b.bookingId());
        if (b.tipAmount().signum() > 0) {
            jdbc.sql("INSERT INTO tips (booking_id, customer_id, amount) VALUES (:b, :c, :amount)")
                .param("b", b.bookingId()).param("c", b.customerId()).param("amount", b.tipAmount()).update();
        }
        events.publishEvent(new PaymentReceived(paymentId, b.bookingId(), b.bookingRef(), b.customerId(), b.genieId(),
            b.totalAmount(), "BOOKING", cash ? "CASH" : "ONLINE"));
    }

    private Optional<PaymentRow> lockPayment(UUID paymentId) {
        return jdbc.sql("""
                SELECT id, booking_id, customer_id, purpose, provider, provider_order_id, amount, status FROM payments
                 WHERE id = :id FOR UPDATE
                """)
            .param("id", paymentId).query(PaymentRow.class).optional();
    }

    private void fail(UUID paymentId, String reason) {
        jdbc.sql("""
                UPDATE payments SET status = 'FAILED', failure_reason = :reason, updated_at = now()
                 WHERE id = :id AND status = 'PENDING'
                """)
            .param("reason", reason).param("id", paymentId).update();
    }
}
