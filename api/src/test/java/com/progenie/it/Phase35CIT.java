package com.progenie.it;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.progenie.booking.BookingReworks;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3.5C: several services from the same Genie in one booking. Priced per line with one travel fee, a slot as long
 * as all of them, coupons on the lines they cover, the customer removing a service before the Genie sets off,
 * complaints naming services, receipts and free redos with every line.
 */
class Phase35CIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    BookingReworks reworks;

    ApiClient customer;
    ApiClient genie;
    ApiClient admin;
    String customerId;
    String genieId;
    String addressId;
    double lat;
    double lng;
    final List<Service> services = new ArrayList<>();

    record Service(long id, BigDecimal price, int minutes) {
    }

    @BeforeEach
    void setUp() {
        customer = new ApiClient(port).login("customer@example.com");
        genie = new ApiClient(port).login("genie@example.com");
        admin = new ApiClient(port).login("admin@progenie.in");
        customerId = customer.get("/api/v1/me").body().get("id").asString();
        genieId = genie.get("/api/v1/genie/profile").body().get("id").asString();
        JsonNode home = customer.get("/api/v1/me/addresses").body().get(0);
        addressId = home.get("id").asString();
        lat = home.get("lat").asDouble();
        lng = home.get("lng").asDouble();
        services.clear();
        jdbc.sql("SELECT gs.service_id, coalesce(gs.price_override, s.base_price) AS price, s.duration_minutes FROM genie_services gs JOIN services s ON s.id = gs.service_id AND s.is_active WHERE gs.genie_id = CAST(:g AS uuid) AND gs.is_active ORDER BY s.duration_minutes, gs.service_id")
            .param("g", genieId)
            .query((rs, i) -> new Service(rs.getLong(1), rs.getBigDecimal(2), rs.getInt(3))).list()
            .forEach(services::add);
        assertThat(services).as("the demo Genie offers several services").hasSizeGreaterThanOrEqualTo(3);
        jdbc.sql("UPDATE bookings SET cancellation_fee_status = 'WAIVED' WHERE cancellation_fee_status = 'DUE' AND customer_id = CAST(:c AS uuid)")
            .param("c", customerId).update();
    }

    @Test
    void oneBookingHoldsSeveralServicesAndTheCustomerCanDropOneBeforeTheGenieSetsOff() {
        Service a = services.get(0);
        Service b = services.get(1);
        String items = a.id() + ":2," + b.id() + ":1";

        // the quote: each line at the Genie's price × quantity, one travel fee, the slot as long as all of them
        JsonNode single = customer.get(estimate("serviceId=" + a.id())).body();
        JsonNode quote = customer.get(estimate("items=" + items)).body();
        assertThat(quote.get("lines")).hasSize(2);
        assertThat(quote.get("lines").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(money(quote, "serviceAmount")).isEqualByComparingTo(a.price().multiply(BigDecimal.TWO).add(b.price()));
        assertThat(money(quote, "travelFee")).as("one travel fee").isEqualByComparingTo(money(single, "travelFee"));
        assertThat(quote.get("durationMinutes").asInt()).isEqualTo(2 * a.minutes() + b.minutes());
        assertThat(code(customer.get(estimate("items=1,2,3,4,5,6")))).isEqualTo("TOO_MANY_SERVICES");
        assertThat(code(customer.get(estimate("items=" + a.id() + ":6")))).isEqualTo("INVALID_QUANTITY");
        assertThat(code(customer.get(estimate("items=" + a.id() + "," + a.id())))).isEqualTo("DUPLICATE_SERVICE");
        assertThat(code(customer.get(estimate("items=" + a.id() + ",999999")))).isEqualTo("SERVICE_NOT_OFFERED");
        Service longest = services.getLast();
        int units = Math.min(5, 480 / longest.minutes() + 1);
        if (units * longest.minutes() > 480) {
            assertThat(code(customer.get(estimate("items=" + longest.id() + ":" + units)))).isEqualTo("JOB_TOO_LONG");
        }

        // a coupon for the second service only works on that line
        String coupon = "LINE" + ThreadLocalRandom.current().nextInt(1_000_000);
        assertThat(admin.post("/api/v1/admin/coupons", """
            {"code":"%s","title":"Half off","discountType":"PERCENT","discountValue":50,"minOrder":0,"perCustomerLimit":5,
             "scope":"SERVICE","serviceId":%d,"listed":false}""".formatted(coupon, b.id())).status()).isEqualTo(201);
        JsonNode checked = customer.post("/api/v1/coupons/check", """
            {"code":"%s","genieId":"%s","items":[{"serviceId":%d,"quantity":2},{"serviceId":%d,"quantity":1}],"addressId":"%s"}"""
            .formatted(coupon, genieId, a.id(), b.id(), addressId)).body();
        BigDecimal half = b.price().divide(BigDecimal.TWO, 2, java.math.RoundingMode.HALF_UP);
        assertThat(money(checked, "discount")).isEqualByComparingTo(half);

        JsonNode booking = book(items, coupon);
        String id = booking.get("id").asString();
        assertThat(booking.get("items")).hasSize(2);
        assertThat(booking.get("serviceName").asString()).isEqualTo(booking.get("items").get(0).get("serviceName").asString()
            + " ×2 + " + booking.get("items").get(1).get("serviceName").asString());
        assertThat(minutes(booking)).isEqualTo(2 * a.minutes() + b.minutes());
        assertThat(money(booking, "discountAmount")).isEqualByComparingTo(half);
        assertThat(money(booking.get("items").get(0), "discountAmount")).isEqualByComparingTo("0");
        assertThat(money(booking.get("items").get(1), "discountAmount")).isEqualByComparingTo(half);
        assertThat(texts(booking.get("allowedActions"))).contains("REMOVE_SERVICE");
        JsonNode job = genie.get("/api/v1/genie/bookings/" + id).body();
        assertThat(job.get("items")).hasSize(2);
        assertThat(money(job, "commissionAmount")).isEqualByComparingTo(
            money(quote.get("lines").get(0), "commissionAmount").add(money(quote.get("lines").get(1), "commissionAmount")));
        String listed = "";
        for (JsonNode row : customer.get("/api/v1/bookings?scope=ACTIVE&size=50").body().get("items")) {
            if (row.get("id").asString().equals(id)) {
                listed = row.get("serviceName").asString();
            }
        }
        assertThat(listed).as("lists name every service").isEqualTo(booking.get("serviceName").asString());

        // dropping the discounted service: shorter, cheaper, and the coupon use is given back
        assertThat(genie.post("/api/v1/genie/bookings/" + id + "/accept", null).status()).isEqualTo(200);
        long bItem = booking.get("items").get(1).get("id").asLong();
        JsonNode after = customer.delete("/api/v1/bookings/" + id + "/items/" + bItem).body();
        assertThat(after.get("items")).hasSize(1);
        assertThat(after.get("status").asString()).as("still accepted").isEqualTo("ACCEPTED");
        assertThat(money(after, "serviceAmount")).isEqualByComparingTo(a.price().multiply(BigDecimal.TWO));
        assertThat(minutes(after)).isEqualTo(2 * a.minutes());
        assertThat(money(after, "discountAmount")).isEqualByComparingTo("0");
        assertThat(after.get("couponCode").isNull()).isTrue();
        assertThat(after.get("serviceName").asString()).doesNotContain(" + ");
        assertThat(texts(after.get("allowedActions"))).doesNotContain("REMOVE_SERVICE");
        assertThat(jdbc.sql("SELECT status FROM coupon_redemptions WHERE booking_id = CAST(:b AS uuid)").param("b", id)
            .query(String.class).single()).isEqualTo("RELEASED");
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'BOOKING_CHANGED' AND link LIKE :l").param("l", "%" + id)
            .query(Integer.class).single()).as("the Genie is told").isEqualTo(1);
        assertThat(texts(after.get("history").findValues("reason"))).anyMatch(r -> r.startsWith("Removed "));
        assertThat(code(customer.delete("/api/v1/bookings/" + id + "/items/" + after.get("items").get(0).get("id").asLong())))
            .isEqualTo("LAST_SERVICE");

        // not once the Genie has set off
        JsonNode two = book(a.id() + ":1," + b.id() + ":1", null);
        jdbc.sql("UPDATE bookings SET status = 'ON_THE_WAY', accepted_at = now(), travel_started_at = now() WHERE id = CAST(:b AS uuid)")
            .param("b", two.get("id").asString()).update();
        assertThat(code(customer.delete("/api/v1/bookings/" + two.get("id").asString() + "/items/"
            + two.get("items").get(1).get("id").asLong()))).isEqualTo("SERVICES_LOCKED");
        cancelByDatabase(id, two.get("id").asString());
    }

    @Test
    void complaintsNameServicesAndReceiptsAndRedosCarryEveryLine() {
        Service a = services.get(0);
        Service c = services.get(2);
        JsonNode booking = book(a.id() + ":1," + c.id() + ":2", null);
        String id = booking.get("id").asString();
        assertThat(genie.post("/api/v1/genie/bookings/" + id + "/accept", null).status()).isEqualTo(200);
        jdbc.sql("UPDATE bookings SET status = 'IN_PROGRESS', started_at = now() WHERE id = CAST(:b AS uuid)").param("b", id).update();
        assertThat(genie.post("/api/v1/genie/bookings/" + id + "/complete", "{\"cashCollected\":true}").status()).isEqualTo(200);

        // the receipt has one line per service
        JsonNode receipts = customer.get("/api/v1/bookings/" + id + "/receipts").body();
        assertThat(receipts.size()).isGreaterThanOrEqualTo(1);
        byte[] pdf = customer.get("/api/v1/receipts/" + receipts.get(0).get("id").asString() + "/pdf").raw();
        try (PDDocument d = PDDocument.load(pdf)) {
            String text = new PDFTextStripper().getText(d);
            assertThat(text).contains(booking.get("items").get(0).get("serviceName").asString())
                .contains(booking.get("items").get(1).get("serviceName").asString())
                .contains("2 \u00d7 Rs. " + c.price().setScale(2).toPlainString());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }

        long cItem = booking.get("items").get(1).get("id").asLong();
        assertThat(code(customer.post("/api/v1/bookings/" + id + "/tickets", """
            {"category":"QUALITY","subject":"Lights flicker","description":"Both new lights flicker.","itemIds":[999999]}""")))
            .isEqualTo("INVALID_ITEMS");
        JsonNode ticket = customer.post("/api/v1/bookings/" + id + "/tickets", """
            {"category":"QUALITY","subject":"Lights flicker","description":"Both new lights flicker.","itemIds":[%d]}"""
            .formatted(cItem)).body();
        assertThat(ticket.get("items")).hasSize(1);
        assertThat(ticket.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(money(ticket.get("items").get(0), "amount")).isEqualByComparingTo(c.price().multiply(BigDecimal.TWO));
        String ticketId = ticket.get("id").asString();
        assertThat(admin.get("/api/v1/admin/tickets/" + ticketId).body().get("items")).hasSize(1);

        // a free redo covers every service, at no charge
        OffsetDateTime slot = freeSlot(a.id() + ":1," + c.id() + ":2");
        UUID adminId = UUID.fromString(admin.get("/api/v1/me").body().get("id").asString());
        UUID redo = reworks.create(UUID.fromString(id), null, slot.toInstant(), adminId);
        JsonNode redone = admin.get("/api/v1/admin/bookings/" + redo).body();
        assertThat(redone.get("items")).hasSize(2);
        assertThat(money(redone, "totalAmount")).isEqualByComparingTo("0");
        assertThat(minutes(redone)).isEqualTo(a.minutes() + 2 * c.minutes());
        assertThat(redone.get("serviceName").asString()).isEqualTo(booking.get("serviceName").asString());
        jdbc.sql("UPDATE tickets SET status = 'CLOSED', closed_at = now() WHERE id = CAST(:t AS uuid)").param("t", ticketId).update();
        cancelByDatabase(redo.toString());
    }

    // ------------------------------------------------------------------ helpers

    private String estimate(String what) {
        return "/api/v1/pricing/estimate?genieId=" + genieId + "&" + what + "&lat=" + lat + "&lng=" + lng;
    }

    private JsonNode book(String items, String coupon) {
        OffsetDateTime slot = freeSlot(items);
        StringBuilder lines = new StringBuilder();
        for (String part : items.split(",")) {
            String[] p = part.split(":");
            lines.append(lines.isEmpty() ? "" : ",").append("{\"serviceId\":").append(p[0]).append(",\"quantity\":")
                .append(p.length > 1 ? p[1] : "1").append('}');
        }
        ApiClient.Response r = customer.post("/api/v1/bookings", """
            {"genieId":"%s","items":[%s],"addressId":"%s","slotStart":"%s","paymentMethod":"CASH","tipAmount":0,"couponCode":%s}"""
            .formatted(genieId, lines, addressId, slot, coupon == null ? "null" : "\"" + coupon + "\""));
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body();
    }

    private OffsetDateTime freeSlot(String items) {
        for (JsonNode day : customer.get("/api/v1/genies/" + genieId + "/slot-days?items=" + items + "&days=14").body()) {
            if (day.get("available").asInt() == 0) {
                continue;
            }
            JsonNode slots = customer.get("/api/v1/genies/" + genieId + "/slots?items=" + items + "&date="
                + day.get("date").asString()).body();
            int expected = 0;
            for (String part : items.split(",")) {
                String[] p = part.split(":");
                long sid = Long.parseLong(p[0]);
                int qty = p.length > 1 ? Integer.parseInt(p[1]) : 1;
                expected += services.stream().filter(s -> s.id() == sid).findFirst().orElseThrow().minutes() * qty;
            }
            assertThat(slots.get("durationMinutes").asInt()).isEqualTo(expected);
            if (!slots.get("slots").isEmpty()) {
                return OffsetDateTime.parse(slots.get("slots").get(slots.get("slots").size() / 2).get("start").asString());
            }
        }
        throw new IllegalStateException("no free slot for " + items);
    }

    private void cancelByDatabase(String... ids) {
        for (String id : ids) {
            jdbc.sql("UPDATE bookings SET status = 'CANCELLED', cancelled_at = now() WHERE id = CAST(:b AS uuid) AND status <> 'COMPLETED'")
                .param("b", id).update();
        }
    }

    private static long minutes(JsonNode booking) {
        return Duration.between(OffsetDateTime.parse(booking.get("slotStart").asString()),
            OffsetDateTime.parse(booking.get("slotEnd").asString())).toMinutes();
    }

    private static BigDecimal money(JsonNode node, String field) {
        return new BigDecimal(node.get(field).asString());
    }

    private static String code(ApiClient.Response r) {
        return r.body() == null || r.body().get("code") == null ? "HTTP " + r.status() : r.body().get("code").asString();
    }

    private static List<String> texts(List<JsonNode> nodes) {
        List<String> out = new ArrayList<>();
        nodes.forEach(n -> out.add(n.isNull() ? "" : n.asString()));
        return out;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }
}
