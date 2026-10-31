package com.progenie.it;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3C-10: live "on the way" tracking over HTTP, including the privacy clean-up. */
class Phase3CIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Test
    void theCustomerFollowsTheGenieUntilTheJobStartsAndThePositionIsThenDeleted() {
        ApiClient customer = new ApiClient(port).login("customer@example.com");
        ApiClient genie = new ApiClient(port).login("genie@example.com");
        String id = bookAndAccept(customer, genie);
        String jobs = "/api/v1/genie/bookings/" + id;

        // too early: the slot is far away
        moveSlotToSoon(id, 2 * 24 * 60);
        assertThat(genie.post(jobs + "/travel", null).body().get("code").asString()).isEqualTo("TOO_EARLY");
        assertThat(genie.put(jobs + "/location", "{\"lat\":23.03,\"lng\":72.56}").body().get("code").asString())
            .isEqualTo("NOT_ON_THE_WAY");

        // 20 minutes before the slot the Genie sets off (and may start once there)
        moveSlotToSoon(id, 20);
        assertThat(texts(genie.get(jobs).body().get("allowedActions"))).contains("START_TRAVEL", "CANCEL");
        JsonNode travelling = genie.post(jobs + "/travel", null).body();
        assertThat(travelling.get("status").asString()).isEqualTo("ON_THE_WAY");
        assertThat(travelling.get("travelStartedAt").isNull()).isFalse();
        assertThat(texts(travelling.get("allowedActions"))).contains("SHARE_LOCATION", "START", "CANCEL").doesNotContain("START_TRAVEL");

        JsonNode before = customer.get("/api/v1/bookings/" + id + "/tracking").body();
        assertThat(before.get("active").asBoolean()).isTrue();
        assertThat(before.get("lat").isNull()).isTrue();
        assertThat(texts(customer.get("/api/v1/bookings/" + id).body().get("allowedActions"))).contains("TRACK", "START_CODE");

        // positions: only the latest is kept, with distance and arrival estimate
        assertThat(genie.put(jobs + "/location", "{\"lat\":23.0319,\"lng\":72.5611,\"accuracyM\":12}").status()).isEqualTo(200);
        ApiClient.Response second = genie.put(jobs + "/location", "{\"lat\":23.0500,\"lng\":72.5550,\"accuracyM\":8}");
        assertThat(second.status()).isEqualTo(200);
        JsonNode map = customer.get("/api/v1/bookings/" + id + "/tracking").body();
        assertThat(map.get("lat").asDouble()).isEqualTo(23.05);

        // a laptop without GPS reports a km-wide guess: still accepted (shown as approximate), never a 400
        ApiClient.Response vague = genie.put(jobs + "/location", "{\"lat\":23.0500,\"lng\":72.5550,\"accuracyM\":150000}");
        assertThat(vague.status()).as(String.valueOf(vague.body())).isEqualTo(200);
        assertThat(vague.body().get("accuracyM").decimalValue()).isEqualByComparingTo("150000");
        // a real mistake gets the API's own error with the field, not Spring's generic "Invalid request content."
        ApiClient.Response bad = genie.put(jobs + "/location", "{\"lat\":95,\"lng\":72.5550}");
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.body().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(bad.body().get("errors").has("lat")).isTrue();
        genie.put(jobs + "/location", "{\"lat\":23.0500,\"lng\":72.5550,\"accuracyM\":8}");
        assertThat(map.get("distanceKm").decimalValue()).isPositive();
        assertThat(map.get("etaMinutes").asInt()).isPositive();
        assertThat(map.get("distanceMethod").asString()).isIn("ROAD", "ESTIMATE");
        assertThat(map.get("customerLat").isNull()).isFalse();
        assertThat(trackingRows(id)).isEqualTo(1);

        // someone else's booking stays private
        ApiClient other = new ApiClient(port).login("9909000001");
        assertThat(other.put(jobs + "/location", "{\"lat\":23.03,\"lng\":72.56}").status()).isEqualTo(404);
        assertThat(new ApiClient(port).login("admin@progenie.in").get("/api/v1/admin/bookings/" + id + "/tracking").status())
            .isEqualTo(200);

        // the job starts: the position is deleted and no longer shown
        String code = customer.post("/api/v1/bookings/" + id + "/start-code", null).body().get("code").asString();
        ApiClient.Response started = genie.post(jobs + "/start", "{\"code\":\"" + code + "\"}");
        assertThat(started.status()).as(String.valueOf(started.body())).isEqualTo(200);
        assertThat(started.body().get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(trackingRows(id)).isZero();
        JsonNode after = customer.get("/api/v1/bookings/" + id + "/tracking").body();
        assertThat(after.get("active").asBoolean()).isFalse();
        assertThat(after.get("lat").isNull()).isTrue();
        genie.post(jobs + "/complete", "{\"cashCollected\":true}");
    }

    @Test
    void cancellingWhileOnTheWayDeletesThePosition() {
        ApiClient customer = new ApiClient(port).login("customer@example.com");
        ApiClient genie = new ApiClient(port).login("genie@example.com");
        String id = bookAndAccept(customer, genie);
        moveSlotToSoon(id, 140); // more than 2 h away, so cancelling is still free
        genie.post("/api/v1/genie/bookings/" + id + "/travel", null);
        genie.put("/api/v1/genie/bookings/" + id + "/location", "{\"lat\":23.04,\"lng\":72.55}");
        assertThat(trackingRows(id)).isEqualTo(1);
        assertThat(customer.post("/api/v1/bookings/" + id + "/cancel", "{\"reason\":\"IT\"}").body().get("status").asString())
            .isEqualTo("CANCELLED");
        assertThat(trackingRows(id)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    /** Moves the slot to {@code minutes} from now (different per test, so the Genie's slots never overlap). */
    private void moveSlotToSoon(String id, int minutes) {
        // leftovers of earlier runs in that window would block the move (the Genie can't be in two places)
        jdbc.sql("UPDATE bookings o SET status = 'CANCELLED', cancelled_at = now() FROM bookings b WHERE b.id = CAST(:b AS uuid) AND o.genie_id = b.genie_id AND o.id <> b.id AND o.status IN ('REQUESTED', 'ACCEPTED', 'ON_THE_WAY', 'IN_PROGRESS') AND tstzrange(o.slot_start, o.slot_end) && tstzrange(now() + make_interval(mins => :m), now() + make_interval(mins => :m + 30))")
            .param("m", minutes).param("b", id).update();
        jdbc.sql("UPDATE bookings SET slot_start = now() + make_interval(mins => :m), slot_end = now() + make_interval(mins => :m + 30) WHERE id = CAST(:b AS uuid)")
            .param("m", minutes).param("b", id).update();
    }

    private int trackingRows(String id) {
        return jdbc.sql("SELECT count(*) FROM booking_tracking WHERE booking_id = CAST(:b AS uuid)").param("b", id)
            .query(Integer.class).single();
    }

    private String bookAndAccept(ApiClient customer, ApiClient genie) {
        // a late-cancellation fee left by another run would block new bookings
        jdbc.sql("UPDATE bookings SET cancellation_fee_status = 'WAIVED' WHERE cancellation_fee_status = 'DUE' AND customer_id = (SELECT id FROM users WHERE email = 'customer@example.com')").update();
        JsonNode me = genie.get("/api/v1/genie/profile").body();
        String genieId = me.get("id").asString();
        long serviceId = me.get("services").get(0).get("serviceId").asLong();
        String addressId = customer.get("/api/v1/me/addresses").body().get(0).get("id").asString();
        for (JsonNode day : customer.get("/api/v1/genies/" + genieId + "/slot-days?serviceId=" + serviceId + "&days=14").body()) {
            if (day.get("available").asInt() == 0) {
                continue;
            }
            for (JsonNode slot : customer.get("/api/v1/genies/" + genieId + "/slots?serviceId=" + serviceId + "&date="
                + day.get("date").asString()).body().get("slots")) {
                ApiClient.Response booked = customer.post("/api/v1/bookings", """
                    {"genieId":"%s","serviceId":%d,"addressId":"%s","slotStart":"%s","paymentMethod":"CASH","tipAmount":0}"""
                    .formatted(genieId, serviceId, addressId, slot.get("start").asString()));
                if (booked.status() == 201) {
                    String id = booked.body().get("id").asString();
                    assertThat(genie.post("/api/v1/genie/bookings/" + id + "/accept", null).status()).isEqualTo(200);
                    return id;
                }
            }
        }
        throw new IllegalStateException("no free slot");
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }
}
