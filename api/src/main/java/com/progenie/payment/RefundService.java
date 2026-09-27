package com.progenie.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.progenie.booking.BookingPayments;
import com.progenie.booking.BookingPayments.Payable;
import com.progenie.payment.PaymentDtos.RefundProcessed;
import com.progenie.payment.PaymentGateway.RefundResult;
import com.progenie.shared.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Refunds (design doc 16.3). A refund belongs to a successful payment and is capped at what was paid minus what
 * was already refunded. Online payments go back through the gateway; cash (or any payment, if the admin chooses)
 * is refunded by hand, e.g. a UPI transfer whose reference the admin records. The admin decides who carries the
 * cost: the Genie, ProGenie (goodwill), or a split. Ledger entries are posted when the money has actually gone.
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    /**
     * @param liability       GENIE, PLATFORM or SPLIT
     * @param genieShare      only for SPLIT: the part the Genie carries
     * @param method          GATEWAY (online payments only) or MANUAL; default: GATEWAY for online, MANUAL for cash
     * @param manualReference UPI / bank reference, required for MANUAL
     */
    public record RefundRequest(BigDecimal amount, String liability, BigDecimal genieShare, String reason, String method,
                                String manualReference) {
    }

    public record RefundDto(UUID id, UUID paymentId, UUID bookingId, BigDecimal amount, BigDecimal genieShare,
                            BigDecimal platformShare, String reason, String method, String status, String providerRefundId,
                            String manualReference, String failureReason, UUID ticketId, OffsetDateTime createdAt,
                            OffsetDateTime processedAt) {
    }

    record PaymentForRefund(UUID id, UUID bookingId, UUID customerId, String purpose, String method, String provider,
                            String providerRef, BigDecimal amount, String status) {
    }

    record RefundRow(UUID id, UUID paymentId, BigDecimal amount, BigDecimal genieShare, BigDecimal platformShare,
                     String method, String status) {
    }

    private static final String SELECT = """
        SELECT id, payment_id, booking_id, amount, genie_share, platform_share, reason, method, status, provider_refund_id,
               manual_reference, failure_reason, ticket_id, created_at, processed_at
          FROM refunds
        """;

    private final JdbcClient jdbc;
    private final BookingPayments bookings;
    private final LedgerService ledger;
    private final PaymentGateway gateway;
    private final ApplicationEventPublisher events;

    public RefundService(JdbcClient jdbc, BookingPayments bookings, LedgerService ledger, PaymentGateway gateway,
                         ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.ledger = ledger;
        this.gateway = gateway;
        this.events = events;
    }

    /** Admin refund, optionally linked to a complaint ticket. */
    @Transactional
    public RefundDto create(UUID adminId, UUID paymentId, RefundRequest req, UUID ticketId) {
        PaymentForRefund p = jdbc.sql("""
                SELECT id, booking_id, customer_id, purpose, method, provider, provider_ref, amount, status
                  FROM payments WHERE id = :id FOR UPDATE
                """)
            .param("id", paymentId).query(PaymentForRefund.class).optional()
            .orElseThrow(() -> ApiException.notFound("PAYMENT_NOT_FOUND", "Payment not found"));
        if (!"SUCCEEDED".equals(p.status())) {
            throw ApiException.unprocessable("NOTHING_TO_REFUND", "Only successful payments can be refunded (this one is "
                + p.status() + ")");
        }
        BigDecimal refundable = refundable(p);
        BigDecimal amount = req.amount() == null ? refundable : req.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.signum() <= 0) {
            throw ApiException.badRequest("INVALID_AMOUNT", "The refund must be more than zero");
        }
        if (amount.compareTo(refundable) > 0) {
            throw ApiException.unprocessable("REFUND_TOO_LARGE", "At most ₹" + refundable + " can still be refunded")
                .with("refundable", refundable);
        }
        String reason = StringUtils.hasText(req.reason()) ? req.reason().trim() : null;
        if (reason == null || reason.length() > 300) {
            throw ApiException.badRequest("REASON_REQUIRED", "Give a reason (up to 300 characters)");
        }

        String liability = req.liability() == null ? "GENIE" : req.liability().toUpperCase(Locale.ROOT);
        BigDecimal genieShare = switch (liability) {
            case "GENIE" -> amount;
            case "PLATFORM" -> BigDecimal.ZERO;
            case "SPLIT" -> {
                BigDecimal g = req.genieShare() == null ? null : req.genieShare().setScale(2, RoundingMode.HALF_UP);
                if (g == null || g.signum() < 0 || g.compareTo(amount) > 0) {
                    throw ApiException.badRequest("INVALID_SPLIT", "The Genie's share must be between ₹0 and ₹" + amount);
                }
                yield g;
            }
            default -> throw ApiException.badRequest("INVALID_LIABILITY", "Liability must be GENIE, PLATFORM or SPLIT");
        };
        BigDecimal platformShare = amount.subtract(genieShare);

        boolean online = "ONLINE".equals(p.method());
        String method = req.method() == null ? (online ? "GATEWAY" : "MANUAL") : req.method().toUpperCase(Locale.ROOT);
        String manualRef = StringUtils.hasText(req.manualReference()) ? req.manualReference().trim() : null;
        if ("GATEWAY".equals(method)) {
            if (!online || p.providerRef() == null || !gateway.name().equals(p.provider())) {
                throw ApiException.unprocessable("GATEWAY_REFUND_NOT_POSSIBLE",
                    "This payment can't be refunded through the gateway; refund it manually (e.g. UPI) and record the reference");
            }
        } else if ("MANUAL".equals(method)) {
            if (manualRef == null || manualRef.length() > 120) {
                throw ApiException.badRequest("REFERENCE_REQUIRED", "Enter the UPI / bank reference of the refund you sent");
            }
        } else {
            throw ApiException.badRequest("INVALID_METHOD", "Method must be GATEWAY or MANUAL");
        }

        Payable b = bookings.lockPayable(p.bookingId());
        UUID refundId = jdbc.sql("""
                INSERT INTO refunds (payment_id, booking_id, amount, genie_share, platform_share, reason, method,
                                     manual_reference, ticket_id, initiated_by)
                VALUES (:p, :b, :amount, :g, :pl, :reason, :method, :ref, :ticket, :admin)
                RETURNING id
                """)
            .param("p", p.id()).param("b", p.bookingId()).param("amount", amount).param("g", genieShare)
            .param("pl", platformShare).param("reason", reason).param("method", method).param("ref", manualRef)
            .param("ticket", ticketId).param("admin", adminId)
            .query(UUID.class).single();

        if ("GATEWAY".equals(method)) {
            RefundResult result = gateway.refund(p.providerRef(), amount, b.bookingRef());
            jdbc.sql("UPDATE refunds SET provider_refund_id = :r WHERE id = :id")
                .param("r", result.refundId()).param("id", refundId).update();
            if (result.processed()) {
                process(refundId, b);
            }
        } else {
            process(refundId, b);
        }
        log.info("Refund {} of ₹{} on {} ({}, Genie ₹{} / ProGenie ₹{})", refundId, amount, b.bookingRef(), method,
            genieShare, platformShare);
        return get(refundId);
    }

    /** refund.processed / refund.failed from the gateway webhook. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onGatewayRefund(String provider, String providerRefundId, boolean processed, String reason) {
        if (providerRefundId == null) {
            return;
        }
        record Found(UUID id, UUID bookingId, String status) {
        }
        Found f = jdbc.sql("""
                SELECT r.id, r.booking_id, r.status FROM refunds r JOIN payments p ON p.id = r.payment_id
                 WHERE r.provider_refund_id = :r AND p.provider = :provider FOR UPDATE OF r
                """)
            .param("r", providerRefundId).param("provider", provider).query(Found.class).optional().orElse(null);
        if (f == null || !"PENDING".equals(f.status())) {
            return;
        }
        if (processed) {
            process(f.id(), bookings.lockPayable(f.bookingId()));
        } else {
            jdbc.sql("UPDATE refunds SET status = 'FAILED', failure_reason = :reason WHERE id = :id")
                .param("reason", reason == null ? "Refund failed at the gateway" : reason).param("id", f.id()).update();
            log.warn("Refund {} failed at the gateway: {}", f.id(), reason);
        }
    }

    @Transactional(readOnly = true)
    public List<RefundDto> forBooking(UUID bookingId) {
        return jdbc.sql(SELECT + " WHERE booking_id = :b ORDER BY created_at DESC").param("b", bookingId)
            .query(RefundDto.class).list();
    }

    @Transactional(readOnly = true)
    public RefundDto get(UUID refundId) {
        return jdbc.sql(SELECT + " WHERE id = :id").param("id", refundId).query(RefundDto.class).optional()
            .orElseThrow(() -> ApiException.notFound("REFUND_NOT_FOUND", "Refund not found"));
    }

    /** What can still be refunded on this payment (pending refunds count as used). */
    BigDecimal refundable(PaymentForRefund p) {
        BigDecimal used = jdbc.sql("""
                SELECT coalesce(sum(amount), 0) FROM refunds WHERE payment_id = :p AND status IN ('PENDING', 'PROCESSED')
                """)
            .param("p", p.id()).query(BigDecimal.class).single();
        return p.amount().subtract(used).max(BigDecimal.ZERO);
    }

    /** The money has gone: post the ledger, update payment and booking status, tell everyone. */
    private void process(UUID refundId, Payable b) {
        RefundRow r = jdbc.sql("""
                SELECT id, payment_id, amount, genie_share, platform_share, method, status FROM refunds WHERE id = :id
                """)
            .param("id", refundId).query(RefundRow.class).single();
        PaymentForRefund p = jdbc.sql("""
                SELECT id, booking_id, customer_id, purpose, method, provider, provider_ref, amount, status
                  FROM payments WHERE id = :id
                """)
            .param("id", r.paymentId()).query(PaymentForRefund.class).single();
        jdbc.sql("UPDATE refunds SET status = 'PROCESSED', processed_at = now() WHERE id = :id").param("id", refundId).update();

        BigDecimal commission = "BOOKING".equals(p.purpose()) ? b.commissionAmount() : BigDecimal.ZERO;
        ledger.postRefund(LedgerPostings.refund(b.bookingId(), b.genieId(), b.bookingRef(), r.genieShare(),
            r.platformShare(), p.amount(), commission), refundId);

        BigDecimal refunded = jdbc.sql("SELECT coalesce(sum(amount), 0) FROM refunds WHERE payment_id = :p AND status = 'PROCESSED'")
            .param("p", p.id()).query(BigDecimal.class).single();
        boolean full = refunded.compareTo(p.amount()) >= 0;
        if (full) {
            jdbc.sql("UPDATE payments SET status = 'REFUNDED', updated_at = now() WHERE id = :id").param("id", p.id()).update();
        }
        if ("BOOKING".equals(p.purpose())) {
            bookings.markRefunded(b.bookingId(), full);
        }
        events.publishEvent(new RefundProcessed(refundId, p.id(), b.bookingId(), b.bookingRef(), b.customerId(),
            b.genieId(), r.amount(), r.genieShare(), r.method()));
    }
}
