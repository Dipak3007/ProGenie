package com.progenie.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Fixed inputs; the expected HMACs were computed independently (Python hmac/hashlib). */
class RazorpayGatewayTest {

    private final RazorpayGateway gateway = new RazorpayGateway(
        new RazorpayProperties("rzp_test_key", "test_secret", "whsec", "https://api.razorpay.com"), JsonMapper.builder().build());

    @Test
    void checkoutSignatureIsHmacOfOrderAndPayment() {
        String sig = "15656b40fea6f2159b578efa459e969de9f5e223fb8a08393e274ac578d9d005";
        assertThat(gateway.verifyPaymentSignature("order_ABC", "pay_XYZ", sig)).isTrue();
        assertThat(gateway.verifyPaymentSignature("order_ABC", "pay_OTHER", sig)).isFalse();
        assertThat(gateway.verifyPaymentSignature("order_ABC", "pay_XYZ", null)).isFalse();
    }

    @Test
    void webhookSignatureIsHmacOfTheRawBody() {
        String body = "{\"event\":\"payment.captured\"}";
        assertThat(gateway.verifyWebhookSignature(body, "4673dd707ef4c41b987cb7fefe1583142dc702388c93145b7814b9ad3d3c183e")).isTrue();
        assertThat(gateway.verifyWebhookSignature(body + " ", "4673dd707ef4c41b987cb7fefe1583142dc702388c93145b7814b9ad3d3c183e")).isFalse();
    }

    @Test
    void readsRazorpayWebhookPayloads() {
        var captured = gateway.parseWebhook("""
            {"entity":"event","event":"payment.captured","payload":{"payment":{"entity":
              {"id":"pay_1","order_id":"order_1","status":"captured"}}}}""", "evt_42");
        assertThat(captured.eventId()).isEqualTo("evt_42");
        assertThat(captured.type()).isEqualTo("payment.captured");
        assertThat(captured.orderId()).isEqualTo("order_1");
        assertThat(captured.paymentId()).isEqualTo("pay_1");
        var refund = gateway.parseWebhook("""
            {"event":"refund.processed","payload":{"refund":{"entity":{"id":"rfnd_9","payment_id":"pay_1","status":"processed"}}}}""",
            null);
        assertThat(refund.refundId()).isEqualTo("rfnd_9");
        assertThat(refund.paymentId()).isEqualTo("pay_1");
        assertThat(refund.eventId()).isEqualTo("refund.processed:rfnd_9");
    }

    @Test
    void amountsGoToRazorpayInPaise() {
        assertThat(RazorpayGateway.toPaise(new BigDecimal("499"))).isEqualTo(49900);
        assertThat(RazorpayGateway.toPaise(new BigDecimal("12.345"))).isEqualTo(1235);
    }

    @Test
    void refusesToStartWithoutKeys() {
        assertThatThrownBy(() -> new RazorpayGateway(new RazorpayProperties("", "", "", "x"), JsonMapper.builder().build()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("RAZORPAY_KEY_ID");
    }
}
