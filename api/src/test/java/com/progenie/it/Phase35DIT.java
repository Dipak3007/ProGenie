package com.progenie.it;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

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
 * Phase 3.5D: big jobs start with an inspection visit (city fee, no commission, 30 minutes); after it the Genie sends a
 * quote with catalog and custom lines, can revise it, and the customer accepts it by picking a time (an accepted job
 * with the quote's lines and the inspection fee taken off) or declines it. Unanswered quotes expire.
 */
class Phase35DIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    ApiClient customer;
    ApiClient genie;
    ApiClient admin;
    String customerId;
    String genieId;
    String addressId;
    double lat;
    double lng;
    long wiring;      // inspection first
    long fan;         // a normal service of the same Genie
    BigDecimal fanPrice;
    BigDecimal fee;

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
        wiring = jdbc.sql("SELECT id FROM services WHERE slug = 'house-wiring-check'").query(Long.class).single();
        fan = jdbc.sql("SELECT id FROM services WHERE slug = 'fan-installation'").query(Long.class).single();
        fanPrice = jdbc.sql("SELECT coalesce(gs.price_override, s.base_price) FROM genie_services gs JOIN services s ON s.id = gs.service_id WHERE gs.genie_id = CAST(:g AS uuid) AND s.id = :s")
            .param("g", genieId).param("s", fan).query(BigDecimal.class).single();
        fee = jdbc.sql("SELECT cp.inspection_fee FROM city_pricing cp JOIN cities c ON c.id = cp.city_id WHERE c.slug = 'ahmedabad'")
            .query(BigDecimal.class).single();
        jdbc.sql("UPDATE bookings SET cancellation_fee_status = 'WAIVED' WHERE cancellation_fee_status = 'DUE' AND customer_id = CAST(:c AS uuid)")
            .param("c", customerId).update();
    }

    @Test
    void anInspectionLeadsToAQuoteThatTheCustomerBooksAsOneJob() {
        // the service is flagged and priced as an inspection visit
        JsonNode profile = customer.get("/api/v1/genies/" + genieId).body();
        boolean flagged = false;
        for (JsonNode s : profile.get("services")) {
            if (s.get("serviceId").asLong() == wiring) {
                flagged = s.get("inspectionFirst").asBoolean();
            }
        }
        assertThat(flagged).as("the profile marks inspection-first services").isTrue();
        JsonNode est = customer.get("/api/v1/pricing/estimate?genieId=" + genieId + "&items=" + wiring + "&lat=" + lat + "&lng=" + lng).body();
        assertThat(est.get("inspection").asBoolean()).isTrue();
        assertThat(money(est, "serviceAmount")).isEqualByComparingTo(fee);
        assertThat(money(est, "commissionAmount")).isEqualByComparingTo("0");
        assertThat(est.get("durationMinutes").asInt()).isEqualTo(30);

        String slot = freeSlot("items=" + wiring);
        assertThat(code(customer.post("/api/v1/bookings", booking(wiring, slot, "\"WELCOME50\"")))).isEqualTo("COUPON_NOT_APPLICABLE");
        JsonNode insp = customer.post("/api/v1/bookings", booking(wiring, slot, "null")).body();
        String inspId = insp.get("id").asString();
        assertThat(insp.get("kind").asString()).isEqualTo("INSPECTION");
        assertThat(insp.get("serviceName").asString()).startsWith("Inspection: ");
        assertThat(minutes(insp)).isEqualTo(30);
        assertThat(money(insp, "serviceAmount")).isEqualByComparingTo(fee);
        assertThat(insp.get("items")).hasSize(2);
        assertThat(insp.get("items").get(1).get("serviceName").asString()).isEqualTo("Inspection visit");
        assertThat(insp.get("items").get(1).get("serviceId").isNull()).isTrue();

        // the Genie can quote only after the visit
        assertThat(genie.post("/api/v1/genie/bookings/" + inspId + "/accept", null).status()).isEqualTo(200);
        String quote = quoteBody(2, 4500, "Replace 20 m of old wiring", 180);
        assertThat(code(genie.post("/api/v1/genie/bookings/" + inspId + "/quote", quote))).isEqualTo("INSPECTION_NOT_DONE");
        complete(inspId);
        JsonNode job = genie.get("/api/v1/genie/bookings/" + inspId).body();
        assertThat(texts(job.get("allowedActions"))).contains("SEND_QUOTE");
        assertThat(money(job, "commissionAmount")).as("no commission on the inspection").isEqualByComparingTo("0");
        assertThat(code(genie.post("/api/v1/genie/bookings/" + inspId + "/quote", """
            {"lines":[{"quantity":1,"unitPrice":500}],"durationMinutes":60}"""))).isEqualTo("INVALID_QUOTE_LINE");
        assertThat(code(genie.post("/api/v1/genie/bookings/" + inspId + "/quote", """
            {"lines":[{"serviceId":999999,"quantity":1,"unitPrice":500}],"durationMinutes":60}"""))).isEqualTo("SERVICE_NOT_OFFERED");

        JsonNode sent = genie.post("/api/v1/genie/bookings/" + inspId + "/quote", quoteBody(1, 4000, "Replace wiring", 120)).body();
        assertThat(sent.get("quote").get("version").asInt()).isEqualTo(1);
        JsonNode revised = genie.post("/api/v1/genie/bookings/" + inspId + "/quote", quote).body().get("quote");
        assertThat(revised.get("version").asInt()).as("revised until answered").isEqualTo(2);
        assertThat(revised.get("lines")).hasSize(2);
        BigDecimal total = fanPrice.multiply(BigDecimal.TWO).add(new BigDecimal("4500"));
        assertThat(money(revised, "totalAmount")).isEqualByComparingTo(total);
        assertThat(money(revised, "inspectionCredit")).isEqualByComparingTo(fee);
        assertThat(money(revised, "jobAmount")).isEqualByComparingTo(total.subtract(fee));
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'QUOTE_READY' AND link LIKE :l").param("l", "%" + inspId)
            .query(Integer.class).single()).isEqualTo(2);

        // the customer accepts by picking a time: an accepted job with the quote's lines, the fee taken off
        JsonNode mine = customer.get("/api/v1/bookings/" + inspId).body();
        assertThat(texts(mine.get("allowedActions"))).contains("ACCEPT_QUOTE", "DECLINE_QUOTE");
        String quoteId = mine.get("quote").get("id").asString();
        JsonNode days = customer.get("/api/v1/genies/" + genieId + "/slots?quoteId=" + quoteId + "&date="
            + firstDay("quoteId=" + quoteId)).body();
        assertThat(days.get("durationMinutes").asInt()).isEqualTo(180);
        String jobSlot = days.get("slots").get(days.get("slots").size() / 2).get("start").asString();
        ApiClient.Response accepted = customer.post("/api/v1/quotes/" + quoteId + "/accept", """
            {"slotStart":"%s","paymentMethod":"CASH","tipAmount":0}""".formatted(jobSlot));
        assertThat(accepted.status()).as(String.valueOf(accepted.body())).isEqualTo(201);
        JsonNode booked = accepted.body();
        assertThat(booked.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(booked.get("kind").asString()).isEqualTo("JOB");
        assertThat(minutes(booked)).isEqualTo(180);
        assertThat(booked.get("items")).hasSize(2);
        assertThat(booked.get("items").get(1).get("serviceId").isNull()).as("the custom line").isTrue();
        assertThat(money(booked, "serviceAmount")).isEqualByComparingTo(total.subtract(fee));
        assertThat(money(booked, "inspectionCredit")).isEqualByComparingTo(fee);
        assertThat(booked.get("quote").get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(texts(booked.get("allowedActions"))).doesNotContain("REMOVE_SERVICE");
        JsonNode genieView = genie.get("/api/v1/genie/bookings/" + booked.get("id").asString()).body();
        BigDecimal rate = jdbc.sql("SELECT commission_rate FROM bookings WHERE id = CAST(:b AS uuid)").param("b", booked.get("id").asString())
            .query(BigDecimal.class).single();
        assertThat(money(genieView, "commissionAmount")).as("commission on every quoted line")
            .isEqualByComparingTo(fanPrice.multiply(BigDecimal.TWO).multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP)
                .add(new BigDecimal("4500").multiply(rate).setScale(2, java.math.RoundingMode.HALF_UP)));
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'QUOTE_ACCEPTED' AND user_id = CAST(:g AS uuid) AND link LIKE :l")
            .param("g", genieId).param("l", "%" + booked.get("id").asString()).query(Integer.class).single()).isEqualTo(1);
        assertThat(code(customer.post("/api/v1/quotes/" + quoteId + "/accept", """
            {"slotStart":"%s","paymentMethod":"CASH"}""".formatted(jobSlot)))).isEqualTo("QUOTE_CLOSED");
        assertThat(code(genie.post("/api/v1/genie/bookings/" + inspId + "/quote", quote))).isEqualTo("QUOTE_CLOSED");

        // receipts: the visit, then the job with the fee taken off
        complete(booked.get("id").asString());
        assertThat(receiptText(inspId)).contains("Inspection visit");
        assertThat(receiptText(booked.get("id").asString())).contains("Replace 20 m of old wiring").contains("Inspection fee already paid");
    }

    @Test
    void aQuoteCanBeDeclinedAndExpires() {
        String a = completedInspection();
        genie.post("/api/v1/genie/bookings/" + a + "/quote", quoteBody(1, 900, "Small fix", 60));
        String quoteA = customer.get("/api/v1/bookings/" + a).body().get("quote").get("id").asString();
        JsonNode declined = customer.post("/api/v1/quotes/" + quoteA + "/decline", "{\"reason\":\"Too expensive\"}").body();
        assertThat(declined.get("quote").get("status").asString()).isEqualTo("DECLINED");
        assertThat(declined.get("quote").get("declineReason").asString()).isEqualTo("Too expensive");
        assertThat(texts(declined.get("allowedActions"))).doesNotContain("ACCEPT_QUOTE");
        assertThat(jdbc.sql("SELECT count(*) FROM notifications WHERE type = 'QUOTE_DECLINED' AND link LIKE :l").param("l", "%" + a)
            .query(Integer.class).single()).isEqualTo(1);

        String b = completedInspection();
        genie.post("/api/v1/genie/bookings/" + b + "/quote", quoteBody(1, 900, "Small fix", 60));
        String quoteB = customer.get("/api/v1/bookings/" + b).body().get("quote").get("id").asString();
        jdbc.sql("UPDATE quotes SET valid_until = now() - interval '1 minute' WHERE id = CAST(:q AS uuid)").param("q", quoteB).update();
        assertThat(customer.get("/api/v1/bookings/" + b).body().get("quote").get("status").asString()).isEqualTo("EXPIRED");
        assertThat(code(customer.post("/api/v1/quotes/" + quoteB + "/decline", null))).isEqualTo("QUOTE_EXPIRED");
        assertThat(texts(genie.get("/api/v1/genie/bookings/" + b).body().get("allowedActions"))).doesNotContain("SEND_QUOTE");
    }

    @Test
    void adminsFlagServicesAndSetTheCityFee() {
        JsonNode cities = admin.get("/api/v1/admin/cities").body();
        JsonNode city = null;
        for (JsonNode c : cities) {
            if ("ahmedabad".equals(c.get("slug").asString())) {
                city = c;
            }
        }
        assertThat(city).isNotNull();
        assertThat(money(city, "inspectionFee")).isEqualByComparingTo(fee);
        String body = """
            {"commissionRate":%s,"travelFreeKm":%s,"travelBaseFee":%s,"travelPerKm":%s,"travelFeeCap":%s,"inspectionFee":249}"""
            .formatted(city.get("commissionRate").asString(), city.get("travelFreeKm").asString(), city.get("travelBaseFee").asString(),
                city.get("travelPerKm").asString(), city.get("travelFeeCap").asString());
        JsonNode updated = admin.put("/api/v1/admin/cities/" + city.get("cityId").asLong() + "/pricing", body).body();
        assertThat(money(updated, "inspectionFee")).isEqualByComparingTo("249");
        admin.put("/api/v1/admin/cities/" + city.get("cityId").asLong() + "/pricing", body.replace("249", fee.toPlainString()));
        JsonNode services = admin.get("/api/v1/admin/services").body();
        boolean seen = false;
        for (JsonNode s : services) {
            if (s.get("id").asLong() == wiring) {
                seen = s.get("inspectionFirst").asBoolean();
            }
        }
        assertThat(seen).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private String completedInspection() {
        String slot = freeSlot("items=" + wiring);
        String id = customer.post("/api/v1/bookings", booking(wiring, slot, "null")).body().get("id").asString();
        genie.post("/api/v1/genie/bookings/" + id + "/accept", null);
        complete(id);
        return id;
    }

    private void complete(String id) {
        jdbc.sql("UPDATE bookings SET status = 'IN_PROGRESS', started_at = now() WHERE id = CAST(:b AS uuid)").param("b", id).update();
        assertThat(genie.post("/api/v1/genie/bookings/" + id + "/complete", "{\"cashCollected\":true}").status()).isEqualTo(200);
    }

    private String booking(long service, String slot, String coupon) {
        return """
            {"genieId":"%s","serviceId":%d,"addressId":"%s","slotStart":"%s","paymentMethod":"CASH","tipAmount":0,"couponCode":%s}"""
            .formatted(genieId, service, addressId, slot, coupon);
    }

    private String quoteBody(int fans, int custom, String customText, int minutes) {
        return """
            {"lines":[{"serviceId":%d,"quantity":%d,"unitPrice":%s},{"description":"%s","quantity":1,"unitPrice":%d}],
             "durationMinutes":%d,"note":"Includes materials"}""".formatted(fan, fans, fanPrice.toPlainString(), customText, custom, minutes);
    }

    private String firstDay(String what) {
        for (JsonNode day : customer.get("/api/v1/genies/" + genieId + "/slot-days?" + what + "&days=14").body()) {
            if (day.get("available").asInt() > 0) {
                return day.get("date").asString();
            }
        }
        throw new IllegalStateException("no free day");
    }

    private String freeSlot(String what) {
        JsonNode slots = customer.get("/api/v1/genies/" + genieId + "/slots?" + what + "&date=" + firstDay(what)).body().get("slots");
        return slots.get(slots.size() / 2).get("start").asString();
    }

    private String receiptText(String bookingId) {
        JsonNode receipts = customer.get("/api/v1/bookings/" + bookingId + "/receipts").body();
        byte[] pdf = customer.get("/api/v1/receipts/" + receipts.get(0).get("id").asString() + "/pdf").raw();
        try (PDDocument d = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(d);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
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

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }
}
