package com.progenie.payment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.progenie.shared.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Razorpay over its REST API (no SDK): orders, signature checks, webhooks and refunds. Amounts go to
 * Razorpay in paise. Enable automatic capture in the Razorpay dashboard (Settings → Payment capture),
 * otherwise payments stay "authorized" and never reach payment.captured.
 */
@Component
@ConditionalOnProperty(prefix = "progenie.payments", name = "gateway", havingValue = "razorpay")
public class RazorpayGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(RazorpayGateway.class);

    private final RazorpayProperties props;
    private final RestClient http;
    private final JsonMapper json;

    public RazorpayGateway(RazorpayProperties props, JsonMapper json) {
        if (!StringUtils.hasText(props.keyId()) || !StringUtils.hasText(props.keySecret())
            || !StringUtils.hasText(props.webhookSecret())) {
            throw new IllegalStateException("progenie.payments.gateway=razorpay needs RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET "
                + "and RAZORPAY_WEBHOOK_SECRET in infra/.env");
        }
        this.props = props;
        this.json = json;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder()
            .baseUrl(props.baseUrl())
            .requestFactory(factory)
            .defaultHeaders(h -> h.setBasicAuth(props.keyId(), props.keySecret()))
            .build();
        log.info("Razorpay gateway enabled ({} mode)", props.keyId().startsWith("rzp_live_") ? "LIVE" : "test");
    }

    @Override
    public String name() {
        return "razorpay";
    }

    @Override
    public String publicKey() {
        return props.keyId();
    }

    @Override
    public GatewayOrder createOrder(String receipt, BigDecimal amount, String currency) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", toPaise(amount));
        body.put("currency", currency);
        body.put("receipt", receipt.length() > 40 ? receipt.substring(0, 40) : receipt);
        body.put("notes", Map.of("receipt", receipt));
        JsonNode res = post("/v1/orders", body);
        return new GatewayOrder(res.path("id").asString(), amount, currency);
    }

    @Override
    public boolean verifyPaymentSignature(String orderId, String providerPaymentId, String signature) {
        return signature != null && equal(hmac(props.keySecret(), orderId + "|" + providerPaymentId), signature);
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signature) {
        return signature != null && equal(hmac(props.webhookSecret(), rawBody), signature);
    }

    /**
     * Razorpay body: {"event":"payment.captured","payload":{"payment":{"entity":{"id","order_id","error_description"}},
     * "refund":{"entity":{"id","payment_id"}}}}. The event id comes in the X-Razorpay-Event-Id header.
     */
    @Override
    public WebhookEvent parseWebhook(String rawBody, String eventIdHeader) {
        JsonNode root = json.readTree(rawBody);
        String type = root.path("event").asString("");
        JsonNode payment = root.path("payload").path("payment").path("entity");
        JsonNode refund = root.path("payload").path("refund").path("entity");
        String eventId = StringUtils.hasText(eventIdHeader) ? eventIdHeader
            : type + ":" + (refund.isMissingNode() ? payment.path("id").asString("") : refund.path("id").asString(""));
        String paymentId = payment.isMissingNode() ? refund.path("payment_id").asString(null) : payment.path("id").asString(null);
        String reason = payment.path("error_description").asString(null);
        return new WebhookEvent(eventId, type, payment.path("order_id").asString(null), paymentId,
            refund.path("id").asString(null), reason);
    }

    @Override
    public RefundResult refund(String providerPaymentId, BigDecimal amount, String receipt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", toPaise(amount));
        body.put("speed", "normal");
        body.put("receipt", receipt.length() > 40 ? receipt.substring(0, 40) : receipt);
        JsonNode res = post("/v1/payments/" + providerPaymentId + "/refund", body);
        return new RefundResult(res.path("id").asString(), "processed".equals(res.path("status").asString("")));
    }

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            return http.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            String detail = json.readTree(e.getResponseBodyAsString()).path("error").path("description").asString(e.getMessage());
            log.warn("Razorpay {} failed: {} {}", path, e.getStatusCode(), detail);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GATEWAY_ERROR", "Payment gateway error: " + detail);
        } catch (RestClientException e) {
            log.warn("Razorpay {} unreachable: {}", path, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "GATEWAY_UNAVAILABLE", "The payment gateway is not reachable. Please try again");
        }
    }

    static long toPaise(BigDecimal rupees) {
        return rupees.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean equal(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
