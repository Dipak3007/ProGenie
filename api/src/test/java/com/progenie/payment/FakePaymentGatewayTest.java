package com.progenie.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import com.progenie.shared.config.AppProperties;
import org.junit.jupiter.api.Test;

class FakePaymentGatewayTest {

    private final FakePaymentGateway gateway = new FakePaymentGateway(new AppProperties("Asia/Kolkata",
        new AppProperties.Jwt("t", Duration.ofMinutes(15), Duration.ofDays(7), null, null), new AppProperties.Cookie(false),
        new AppProperties.Cors(List.of()), null, new AppProperties.Payments("fake", "test-secret", "INR"), null), tools.jackson.databind.json.JsonMapper.builder().build());

    @Test
    void signedCheckoutResultVerifies() {
        var order = gateway.createOrder("PG-1/BOOKING", new BigDecimal("499.00"), "INR");
        var paid = gateway.simulateCheckout(order.orderId());
        assertThat(gateway.verifyPaymentSignature(order.orderId(), paid.providerPaymentId(), paid.signature())).isTrue();
    }

    @Test
    void tamperedValuesDoNotVerify() {
        var order = gateway.createOrder("PG-1/BOOKING", new BigDecimal("499.00"), "INR");
        var paid = gateway.simulateCheckout(order.orderId());
        assertThat(gateway.verifyPaymentSignature("fake_order_other", paid.providerPaymentId(), paid.signature())).isFalse();
        assertThat(gateway.verifyPaymentSignature(order.orderId(), paid.providerPaymentId(), "0".repeat(64))).isFalse();
        assertThat(gateway.verifyPaymentSignature(order.orderId(), paid.providerPaymentId(), null)).isFalse();
    }

    @Test
    void webhookSignatureCoversTheExactBody() {
        String body = "{\"event\":\"payment.captured\",\"orderId\":\"o1\",\"paymentId\":\"p1\"}";
        String signature = gateway.signWebhook(body);
        assertThat(gateway.verifyWebhookSignature(body, signature)).isTrue();
        assertThat(gateway.verifyWebhookSignature(body.replace("p1", "p2"), signature)).isFalse();
    }
}
