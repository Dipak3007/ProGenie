package com.progenie.it;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.progenie.booking.Carts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3.5E: the cart. Checkout is saved as the customer goes and re-checked on every read (services, address, time,
 * coupon, price), holds one Genie (another replaces it only when asked), is deleted by the booking it leads to, sends
 * one in-app reminder after a day and goes after 30 days. A booking confirms the price the customer saw.
 */
class Phase35EIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    Carts carts;

    @Autowired
    TransactionTemplate tx;

    ApiClient customer;
    String customerId;
    String genieId;
    String addressId;
    long fan;
    long switchboard;

    @BeforeEach
    void setUp() {
        customer = new ApiClient(port).login("customer@example.com");
        ApiClient genie = new ApiClient(port).login("genie@example.com");
        customerId = customer.get("/api/v1/me").body().get("id").asString();
        genieId = genie.get("/api/v1/genie/profile").body().get("id").asString();
        addressId = customer.get("/api/v1/me/addresses").body().get(0).get("id").asString();
        fan = jdbc.sql("SELECT id FROM services WHERE slug = 'fan-installation'").query(Long.class).single();
        switchboard = jdbc.sql("SELECT id FROM services WHERE slug = 'switchboard-repair'").query(Long.class).single();
        jdbc.sql("UPDATE bookings SET cancellation_fee_status = 'WAIVED' WHERE cancellation_fee_status = 'DUE' AND customer_id = CAST(:c AS uuid)")
            .param("c", customerId).update();
        customer.delete("/api/v1/cart");
    }

    @AfterEach
    void restore() {
        jdbc.sql("UPDATE genie_services SET is_active = TRUE, price_override = NULL WHERE genie_id = CAST(:g AS uuid) AND service_id IN (:s)")
            .param("g", genieId).param("s", List.of(fan, switchboard)).update();
    }

    @Test
    void theCartKeepsTheCheckoutAndReportsWhatChanged() {
        assertThat(customer.get("/api/v1/cart").body().get("cart").isNull()).as("no cart yet").isTrue();
        String slot = freeSlot();
        JsonNode saved = customer.put("/api/v1/cart", cart(genieId, fan + ":2," + switchboard + ":1", slot, "null", "null", false)).body();
        JsonNode c = saved.get("cart");
        assertThat(c.get("genieId").asString()).isEqualTo(genieId);
        assertThat(c.get("items")).hasSize(2);
        assertThat(c.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(c.get("itemsParam").asString()).isEqualTo(fan + ":2," + switchboard + ":1");
        assertThat(c.get("slotStart").isNull()).isFalse();
        assertThat(c.get("addressLabel").asString()).isNotBlank();
        BigDecimal total = money(c, "total");
        assertThat(total).isEqualByComparingTo(money(c.get("estimate"), "totalAmount"));
        assertThat(texts(saved.get("notices"))).isEmpty();

        // the same cart after "logging in again": nothing changed, nothing to report
        JsonNode again = customer.get("/api/v1/cart").body();
        assertThat(texts(again.get("notices"))).isEmpty();
        assertThat(money(again.get("cart"), "total")).isEqualByComparingTo(total);

        // the Genie raised a price: the cart shows the new total and says so (once)
        jdbc.sql("UPDATE genie_services gs SET price_override = s.base_price + 50 FROM services s WHERE s.id = gs.service_id AND gs.genie_id = CAST(:g AS uuid) AND gs.service_id = :s")
            .param("g", genieId).param("s", switchboard).update();
        JsonNode changed = customer.get("/api/v1/cart").body();
        assertThat(String.join(" ", texts(changed.get("notices")))).contains("The price changed from");
        assertThat(money(changed.get("cart"), "total")).isGreaterThan(total);
        assertThat(texts(customer.get("/api/v1/cart").body().get("notices"))).as("reported once").isEmpty();

        // a service the Genie stopped offering is dropped; a time in the past is cleared; a dead coupon removed
        jdbc.sql("UPDATE genie_services SET is_active = FALSE WHERE genie_id = CAST(:g AS uuid) AND service_id = :s")
            .param("g", genieId).param("s", switchboard).update();
        jdbc.sql("UPDATE carts SET slot_start = now() - interval '1 day', coupon_code = 'NOPE123' WHERE customer_id = CAST(:c AS uuid)")
            .param("c", customerId).update();
        JsonNode rechecked = customer.get("/api/v1/cart").body();
        String notices = String.join(" ", texts(rechecked.get("notices")));
        assertThat(notices).contains("Switchboard repair is no longer offered").contains("is no longer available")
            .contains("Coupon NOPE123 no longer applies");
        assertThat(rechecked.get("cart").get("items")).hasSize(1);
        assertThat(rechecked.get("cart").get("slotStart").isNull()).isTrue();
        assertThat(rechecked.get("cart").get("couponCode").isNull()).isTrue();

        // one Genie per cart: another Genie replaces it only when the customer says so
        String other = jdbc.sql("SELECT gs.genie_id FROM genie_services gs JOIN genie_profiles gp ON gp.user_id = gs.genie_id JOIN users u ON u.id = gs.genie_id WHERE gs.service_id = :s AND gs.is_active AND gp.verification_status = 'APPROVED' AND u.status = 'ACTIVE' AND gs.genie_id <> CAST(:g AS uuid) LIMIT 1")
            .param("s", fan).param("g", genieId).query(String.class).single();
        ApiClient.Response conflict = customer.put("/api/v1/cart", cart(other, fan + ":1", null, "null", "null", false));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.body().get("code").asString()).isEqualTo("CART_OTHER_GENIE");
        assertThat(conflict.body().get("genieName").asString()).isNotBlank();
        JsonNode replaced = customer.put("/api/v1/cart", cart(other, fan + ":1", null, "null", "null", true)).body();
        assertThat(replaced.get("cart").get("genieId").asString()).isEqualTo(other);
        assertThat(replaced.get("cart").get("total").isNull()).as("no address, no total").isTrue();

        assertThat(customer.delete("/api/v1/cart").status()).isEqualTo(204);
        assertThat(customer.get("/api/v1/cart").body().get("cart").isNull()).isTrue();
    }

    @Test
    void aBookingConfirmsThePriceTheCustomerSawAndEmptiesTheCart() {
        String slot = freeSlot();
        JsonNode c = customer.put("/api/v1/cart", cart(genieId, fan + ":1", slot, "null", "null", false)).body().get("cart");
        BigDecimal total = money(c, "total");

        ApiClient.Response stale = customer.post("/api/v1/bookings", booking(slot, total.subtract(BigDecimal.TEN)));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("code").asString()).isEqualTo("PRICE_CHANGED");
        assertThat(money(stale.body(), "newTotal")).isEqualByComparingTo(total);
        assertThat(customer.get("/api/v1/cart").body().get("cart").isNull()).as("the cart survives a refused booking").isFalse();

        ApiClient.Response ok = customer.post("/api/v1/bookings", booking(slot, total));
        assertThat(ok.status()).isEqualTo(201);
        assertThat(money(ok.body(), "totalAmount")).isEqualByComparingTo(total);
        assertThat(customer.get("/api/v1/cart").body().get("cart").isNull()).as("booked: the cart is done").isTrue();
        customer.post("/api/v1/bookings/" + ok.body().get("id").asString() + "/cancel", "{\"reason\":\"test\"}");
    }

    @Test
    void anUntouchedCartIsRemindedOnceAndExpiresAfter30Days() {
        java.time.OffsetDateTime started = jdbc.sql("SELECT now()").query(java.time.OffsetDateTime.class).single();
        customer.put("/api/v1/cart", cart(genieId, fan + ":1", null, "null", "null", false));
        jdbc.sql("UPDATE carts SET updated_at = now() - interval '25 hours' WHERE customer_id = CAST(:c AS uuid)").param("c", customerId).update();
        tx.executeWithoutResult(s -> carts.remindAndExpire());
        tx.executeWithoutResult(s -> carts.remindAndExpire());
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE user_id = CAST(:c AS uuid) AND type = 'CART_REMINDER' AND created_at >= :t")
            .param("c", customerId).param("t", started).query(Integer.class).single()).as("one reminder").isEqualTo(1);

        jdbc.sql("UPDATE carts SET updated_at = now() - interval '31 days' WHERE customer_id = CAST(:c AS uuid)").param("c", customerId).update();
        tx.executeWithoutResult(s -> carts.remindAndExpire());
        assertThat(jdbc.sql("SELECT count(*) FROM carts WHERE customer_id = CAST(:c AS uuid)").param("c", customerId)
            .query(Integer.class).single()).isZero();
    }

    private String cart(String genie, String items, String slot, String coupon, String seen, boolean replace) {
        StringBuilder lines = new StringBuilder();
        for (String part : items.split(",")) {
            String[] p = part.split(":");
            lines.append(lines.isEmpty() ? "" : ",").append("{\"serviceId\":").append(p[0]).append(",\"quantity\":").append(p[1]).append('}');
        }
        boolean mine = genie.equals(genieId);
        return """
            {"genieId":"%s","items":[%s],"addressId":%s,"slotStart":%s,"paymentMethod":"CASH","tipAmount":0,"couponCode":%s,"seenTotal":%s,"replace":%s}"""
            .formatted(genie, lines, mine ? "\"" + addressId + "\"" : "null", slot == null ? "null" : "\"" + slot + "\"", coupon, seen, replace);
    }

    private String booking(String slot, BigDecimal expected) {
        return """
            {"genieId":"%s","items":[{"serviceId":%d,"quantity":1}],"addressId":"%s","slotStart":"%s","paymentMethod":"CASH","tipAmount":0,"expectedTotal":%s}"""
            .formatted(genieId, fan, addressId, slot, expected.toPlainString());
    }

    private String freeSlot() {
        for (JsonNode day : customer.get("/api/v1/genies/" + genieId + "/slot-days?items=" + fan + ":2," + switchboard + ":1&days=14").body()) {
            if (day.get("available").asInt() > 0) {
                JsonNode slots = customer.get("/api/v1/genies/" + genieId + "/slots?items=" + fan + ":2," + switchboard + ":1&date="
                    + day.get("date").asString()).body().get("slots");
                return slots.get(slots.size() / 2).get("start").asString();
            }
        }
        throw new IllegalStateException("no free day");
    }

    private static BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asString());
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }
}
