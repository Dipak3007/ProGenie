package com.progenie.payment;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.progenie.payment.PaymentDtos.ConfirmPaymentRequest;
import com.progenie.payment.PaymentDtos.PaymentDto;
import com.progenie.payment.PaymentDtos.PaymentOrderDto;
import com.progenie.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Online payments. Flow: POST payment-order → app opens checkout → POST confirm with the gateway's
 * signed result. The webhook is the server-to-server backup of the same result.
 */
@RestController
public class PaymentController {

    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    /** Creates an order for whatever is due: the booking total after the job, or a late-cancellation fee. */
    @PostMapping("/api/v1/bookings/{bookingId}/payment-order")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentOrderDto order(@PathVariable UUID bookingId) {
        return payments.createOrder(CurrentUser.id(), bookingId);
    }

    @GetMapping("/api/v1/bookings/{bookingId}/payments")
    public List<PaymentDto> history(@PathVariable UUID bookingId) {
        return payments.forBooking(CurrentUser.id(), bookingId);
    }

    @PostMapping("/api/v1/payments/{paymentId}/confirm")
    public PaymentDto confirm(@PathVariable UUID paymentId, @Valid @RequestBody ConfirmPaymentRequest req) {
        return payments.confirm(CurrentUser.id(), paymentId, req.providerPaymentId(), req.signature());
    }

    /** Local testing with the fake gateway: ?outcome=success|failure */
    @PostMapping("/api/v1/payments/{paymentId}/simulate")
    public PaymentDto simulate(@PathVariable UUID paymentId, @RequestParam(defaultValue = "success") String outcome) {
        return payments.simulate(CurrentUser.id(), paymentId, !"failure".equalsIgnoreCase(outcome));
    }

    /**
     * Public, but only accepted with a valid signature (HMAC-SHA256 of the raw body): X-Razorpay-Signature from
     * Razorpay, X-Signature from the fake gateway. Configure https://<host>/api/v1/payments/webhook/razorpay in
     * Razorpay with the events payment.captured, payment.failed, refund.processed and refund.failed.
     */
    @PostMapping("/api/v1/payments/webhook/{provider}")
    public Map<String, String> webhook(@PathVariable String provider, @RequestBody String rawBody,
                                       @RequestHeader(name = "X-Signature", required = false) String signature,
                                       @RequestHeader(name = "X-Razorpay-Signature", required = false) String razorpaySignature,
                                       @RequestHeader(name = "X-Razorpay-Event-Id", required = false) String eventId) {
        payments.webhook(provider, rawBody, razorpaySignature != null ? razorpaySignature : signature, eventId);
        return Map.of("status", "ok");
    }
}
