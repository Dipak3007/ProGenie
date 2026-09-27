package com.progenie.payment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.progenie.shared.config.AppProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A local stand-in for a real gateway: no money moves, but the flow and the HMAC-SHA256 signatures
 * are the same shape as Razorpay's (signature = HMAC(secret, orderId + "|" + paymentId)), so a real
 * adapter can replace this class without changing the payment service.
 */
@Component
@ConditionalOnProperty(prefix = "progenie.payments", name = "gateway", havingValue = "fake", matchIfMissing = true)
public class FakePaymentGateway implements PaymentGateway {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] secret;

    private final JsonMapper json;

    public FakePaymentGateway(AppProperties props, JsonMapper json) {
        this.secret = props.payments().webhookSecret().getBytes(StandardCharsets.UTF_8);
        this.json = json;
    }

    /** What the fake checkout would hand back to the app after a successful payment. */
    public record SimulatedPayment(String providerPaymentId, String signature) {
    }

    @Override
    public String name() {
        return "fake";
    }

    @Override
    public GatewayOrder createOrder(String receipt, BigDecimal amount, String currency) {
        return new GatewayOrder("fake_order_" + randomId(), amount, currency);
    }

    @Override
    public boolean verifyPaymentSignature(String orderId, String providerPaymentId, String signature) {
        return signature != null && constantTimeEquals(hmac(orderId + "|" + providerPaymentId), signature);
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        return signature != null && constantTimeEquals(hmac(rawBody), signature);
    }

    /** Fake body: {"id"?, "event", "orderId", "paymentId", "refundId", "reason"}. */
    @Override
    public WebhookEvent parseWebhook(String rawBody, String eventIdHeader) {
        JsonNode b = json.readTree(rawBody);
        String id = eventIdHeader != null ? eventIdHeader : b.path("id").asString(null);
        if (id == null) {
            id = "sha256:" + hmac("event-id:" + rawBody);
        }
        return new WebhookEvent(id, b.path("event").asString(""), b.path("orderId").asString(null),
            b.path("paymentId").asString(null), b.path("refundId").asString(null), b.path("reason").asString(null));
    }

    /** Refunds settle instantly in the fake world. */
    @Override
    public RefundResult refund(String providerPaymentId, BigDecimal amount, String receipt) {
        return new RefundResult("fake_rfnd_" + randomId(), true);
    }

    /** Local testing only: produces what a real checkout would return for the order. */
    public SimulatedPayment simulateCheckout(String orderId) {
        String paymentId = "fake_pay_" + randomId();
        return new SimulatedPayment(paymentId, hmac(orderId + "|" + paymentId));
    }

    /** Local testing only: signs a webhook body like the gateway would. */
    public String signWebhook(String rawBody) {
        return hmac(rawBody);
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String randomId() {
        byte[] bytes = new byte[9];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
