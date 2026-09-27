package com.progenie.payment;

import java.math.BigDecimal;

/**
 * Port to an online payment gateway (Razorpay-style flow): the server creates an order, the app's
 * checkout collects the money, and the result comes back signed (client callback and webhook).
 * Signatures are verified server-side; the client is never trusted with "payment succeeded".
 * Adapters: {@link FakePaymentGateway} (local default) and {@link RazorpayGateway}.
 */
public interface PaymentGateway {

    record GatewayOrder(String orderId, BigDecimal amount, String currency) {
    }

    /**
     * A verified webhook, normalised. {@code type} uses Razorpay's names: payment.captured, payment.failed,
     * refund.processed, refund.failed (anything else is ignored).
     */
    record WebhookEvent(String eventId, String type, String orderId, String paymentId, String refundId, String reason) {
    }

    /** {@code processed} is false when the gateway accepted the refund but settles it later (webhook follows). */
    record RefundResult(String refundId, boolean processed) {
    }

    /** Short provider name stored in payments.provider, e.g. "fake" or "razorpay". */
    String name();

    /** Public key the web checkout needs (Razorpay key id); null for gateways without one. */
    default String publicKey() {
        return null;
    }

    GatewayOrder createOrder(String receipt, BigDecimal amount, String currency);

    /** Verifies the checkout callback signature for (orderId, paymentId). */
    boolean verifyPaymentSignature(String orderId, String providerPaymentId, String signature);

    /** Verifies a webhook call against its raw request body. */
    boolean verifyWebhookSignature(String rawBody, String signature);

    /** Reads a verified webhook body. {@code eventIdHeader} is the provider's event id header, if it sends one. */
    WebhookEvent parseWebhook(String rawBody, String eventIdHeader);

    /** Refunds (part of) a captured payment to the original payment method. */
    RefundResult refund(String providerPaymentId, BigDecimal amount, String receipt);
}
