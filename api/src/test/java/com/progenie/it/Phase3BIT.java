package com.progenie.it;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3B: coupons, from the admin creating one to the ledger entry after the job, over HTTP. */
class Phase3BIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Test
    void aCouponIsAppliedAtBookingGivenBackOnCancelAndPaidByProGenie() {
        ApiClient admin = new ApiClient(port).login("admin@progenie.in");
        ApiClient customer = new ApiClient(port).login("customer@example.com");
        ApiClient genie = new ApiClient(port).login("genie@example.com");
        String run = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String flat = "FLAT" + run;
        String hidden = "HIDE" + run;

        // admin creates a public flat coupon (one use per customer) and a private percentage one
        ApiClient.Response created = admin.post("/api/v1/admin/coupons", """
            {"code":"%s","title":"Rs 40 off","discountType":"FLAT","discountValue":40,"minOrder":0,"perCustomerLimit":1,
             "scope":"ALL","listed":true}""".formatted(flat.toLowerCase()));
        assertThat(created.status()).as(String.valueOf(created.body())).isEqualTo(201);
        assertThat(created.body().get("code").asString()).isEqualTo(flat);
        long flatId = created.body().get("id").asLong();
        assertThat(admin.post("/api/v1/admin/coupons", """
            {"code":"%s","title":"20%% off up to 30","discountType":"PERCENT","discountValue":20,"maxDiscount":30,
             "scope":"ALL","listed":false}""".formatted(hidden)).status()).isEqualTo(201);

        // bad coupons are refused
        assertThat(admin.post("/api/v1/admin/coupons", """
            {"code":"%s","title":"dup","discountType":"FLAT","discountValue":10,"scope":"ALL","listed":true}"""
            .formatted(flat)).body().get("code").asString()).isEqualTo("COUPON_CODE_TAKEN");
        assertThat(admin.post("/api/v1/admin/coupons", """
            {"code":"P%s","title":"too much","discountType":"PERCENT","discountValue":150,"scope":"ALL","listed":true}"""
            .formatted(run)).body().get("code").asString()).isEqualTo("INVALID_COUPON");
        assertThat(admin.post("/api/v1/admin/coupons", """
            {"code":"C%s","title":"no category","discountType":"FLAT","discountValue":10,"scope":"CATEGORY","listed":true}"""
            .formatted(run)).body().get("code").asString()).isEqualTo("INVALID_COUPON");
        assertThat(customer.get("/api/v1/admin/coupons").status()).isEqualTo(403);

        // checkout lists the public coupon (not the private one); a typed private code works
        JsonNode me = genie.get("/api/v1/genie/profile").body();
        String genieId = me.get("id").asString();
        long serviceId = me.get("services").get(0).get("serviceId").asLong();
        String addressId = customer.get("/api/v1/me/addresses").body().get(0).get("id").asString();
        String query = "?genieId=" + genieId + "&serviceId=" + serviceId + "&addressId=" + addressId;
        List<String> listed = codes(customer.get("/api/v1/coupons" + query).body());
        assertThat(listed).contains(flat).doesNotContain(hidden);
        String check = """
            {"code":"%s","genieId":"%s","serviceId":%d,"addressId":"%s"}""";
        ApiClient.Response typed = customer.post("/api/v1/coupons/check", check.formatted(hidden.toLowerCase(), genieId, serviceId, addressId));
        assertThat(typed.status()).isEqualTo(200);
        assertThat(typed.body().get("discount").decimalValue()).isPositive().isLessThanOrEqualTo(new BigDecimal("30"));
        assertThat(customer.post("/api/v1/coupons/check", check.formatted("NOPE" + run, genieId, serviceId, addressId))
            .body().get("code").asString()).isEqualTo("COUPON_NOT_FOUND");

        // booking with the coupon: the customer pays 40 less, the Genie earns the same
        JsonNode first = book(customer, genie, genieId, serviceId, addressId, flat, false);
        BigDecimal discount = first.get("discountAmount").decimalValue();
        assertThat(discount).isEqualByComparingTo("40");
        assertThat(first.get("couponCode").asString()).isEqualTo(flat);
        BigDecimal gross = first.get("serviceAmount").decimalValue().add(first.get("travelFee").decimalValue());
        assertThat(first.get("totalAmount").decimalValue()).isEqualByComparingTo(gross.subtract(discount));

        // one use per customer, until the booking is cancelled
        ApiClient.Response again = bookRaw(customer, genieId, serviceId, addressId, flat);
        assertThat(again.status()).isEqualTo(422);
        assertThat(again.body().get("code").asString()).isEqualTo("COUPON_ALREADY_USED");
        assertThat(customer.post("/api/v1/bookings/" + first.get("id").asString() + "/cancel", "{\"reason\":\"IT\"}").status())
            .isEqualTo(200);

        JsonNode second = book(customer, genie, genieId, serviceId, addressId, flat, true);
        String bookingId = second.get("id").asString();
        JsonNode g = genie.get("/api/v1/genie/bookings/" + bookingId).body();
        BigDecimal commission = g.get("commissionAmount").decimalValue();
        assertThat(g.get("geniePayout").decimalValue()).isEqualByComparingTo(gross.subtract(commission));

        // cash job: ProGenie pays the discount (ledger), so the Genie's wallet moves by discount - commission
        BigDecimal before = genie.get("/api/v1/genie/wallet").body().get("balance").decimalValue();
        jdbc.sql("UPDATE bookings SET status = 'IN_PROGRESS', started_at = now() WHERE id = CAST(:b AS uuid)").param("b", bookingId).update();
        assertThat(genie.post("/api/v1/genie/bookings/" + bookingId + "/complete", "{\"cashCollected\":true}").status()).isEqualTo(200);
        BigDecimal after = genie.get("/api/v1/genie/wallet").body().get("balance").decimalValue();
        assertThat(after.subtract(before)).isEqualByComparingTo(discount.subtract(commission));
        BigDecimal promotions = jdbc.sql("SELECT coalesce(sum(amount), 0) FROM ledger_entries WHERE booking_id = CAST(:b AS uuid) AND account = 'PLATFORM_PROMOTIONS' AND direction = 'D'")
            .param("b", bookingId).query(BigDecimal.class).single();
        assertThat(promotions).isEqualByComparingTo(discount);

        // admin sees the uses; pausing stops new ones
        JsonNode stats = admin.get("/api/v1/admin/coupons/" + flatId).body();
        assertThat(stats.get("uses").asLong()).isEqualTo(1);
        assertThat(stats.get("released").asLong()).isEqualTo(1);
        assertThat(stats.get("totalDiscount").decimalValue()).isEqualByComparingTo("40");
        assertThat(admin.get("/api/v1/admin/coupons/" + flatId + "/redemptions").body().get("items")).hasSize(2);
        assertThat(admin.put("/api/v1/admin/coupons/" + flatId + "/status", "{\"active\":false}").body().get("status").asString())
            .isEqualTo("PAUSED");
        assertThat(codes(customer.get("/api/v1/coupons" + query).body())).doesNotContain(flat);
        assertThat(customer.post("/api/v1/coupons/check", check.formatted(flat, genieId, serviceId, addressId))
            .body().get("code").asString()).isEqualTo("COUPON_PAUSED");
        assertThat(admin.put("/api/v1/admin/coupons/" + flatId, """
            {"code":"OTHER%s","title":"x","discountType":"FLAT","discountValue":10,"scope":"ALL","listed":true}"""
            .formatted(run)).body().get("code").asString()).isEqualTo("COUPON_CODE_LOCKED");
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode book(ApiClient customer, ApiClient genie, String genieId, long serviceId, String addressId, String code,
                          boolean accept) {
        ApiClient.Response res = bookRaw(customer, genieId, serviceId, addressId, code);
        assertThat(res.status()).as(String.valueOf(res.body())).isEqualTo(201);
        if (accept) {
            assertThat(genie.post("/api/v1/genie/bookings/" + res.body().get("id").asString() + "/accept", null).status())
                .isEqualTo(200);
        }
        return res.body();
    }

    /** Books the first free slot of the next 14 days. */
    private ApiClient.Response bookRaw(ApiClient customer, String genieId, long serviceId, String addressId, String code) {
        ApiClient.Response last = null;
        for (JsonNode day : customer.get("/api/v1/genies/" + genieId + "/slot-days?serviceId=" + serviceId + "&days=14").body()) {
            if (day.get("available").asInt() == 0) {
                continue;
            }
            for (JsonNode slot : customer.get("/api/v1/genies/" + genieId + "/slots?serviceId=" + serviceId + "&date="
                + day.get("date").asString()).body().get("slots")) {
                last = customer.post("/api/v1/bookings", """
                    {"genieId":"%s","serviceId":%d,"addressId":"%s","slotStart":"%s","paymentMethod":"CASH","tipAmount":0,
                     "couponCode":"%s"}""".formatted(genieId, serviceId, addressId, slot.get("start").asString(), code));
                if (last.status() != 409) {
                    return last;
                }
            }
        }
        throw new IllegalStateException("no free slot: " + (last == null ? "none" : last.body()));
    }

    private static List<String> codes(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get("code").asString()));
        return out;
    }
}
