package com.progenie.it;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Phase 3D: unmet demand is recorded (without personal data, once per person per 30 minutes), Genie Now says why
 * nobody was found and uses the real travel time, and the admin heatmap groups it all by PIN code.
 */
class Phase3DIT extends IntegrationTest {

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    ApiClient customer;
    ApiClient genie;
    ApiClient admin;
    String genieId;
    long serviceId;
    String addressId;
    List<Map<String, Object>> savedHours;

    @BeforeEach
    void setUp() {
        customer = new ApiClient(port).login("customer@example.com");
        genie = new ApiClient(port).login("genie@example.com");
        admin = new ApiClient(port).login("admin@progenie.in");
        JsonNode me = genie.get("/api/v1/genie/profile").body();
        genieId = me.get("id").asString();
        serviceId = me.get("services").get(0).get("serviceId").asLong();
        addressId = customer.get("/api/v1/me/addresses").body().get(0).get("id").asString();
        // earlier runs: counters that would treat this run's events as repeats, open searches, soon bookings, fees
        jdbc.sql("DELETE FROM rate_limit_counters WHERE key LIKE 'demand:%'").update();
        jdbc.sql("UPDATE instant_requests SET status = 'CANCELLED', ended_at = now() WHERE status = 'SEARCHING'").update();
        jdbc.sql("UPDATE instant_offers SET status = 'WITHDRAWN' WHERE status = 'OFFERED'").update();
        jdbc.sql("UPDATE bookings SET status = 'CANCELLED', cancelled_at = now() WHERE genie_id = CAST(:g AS uuid) AND status IN ('REQUESTED', 'ACCEPTED', 'ON_THE_WAY', 'IN_PROGRESS') AND slot_start < now() + interval '4 hours' AND slot_end > now()")
            .param("g", genieId).update();
        jdbc.sql("UPDATE bookings SET cancellation_fee_status = 'WAIVED' WHERE cancellation_fee_status = 'DUE' AND customer_id = (SELECT id FROM users WHERE email = 'customer@example.com')").update();
        jdbc.sql("UPDATE genie_profiles SET is_online = FALSE WHERE user_id <> CAST(:g AS uuid)").param("g", genieId).update();
        jdbc.sql("UPDATE services SET instant_enabled = TRUE WHERE id = :s").param("s", serviceId).update();
        jdbc.sql("DELETE FROM genie_time_off WHERE genie_id = CAST(:g AS uuid)").param("g", genieId).update();
        savedHours = jdbc.sql("SELECT day_of_week, start_time, end_time FROM genie_availability WHERE genie_id = CAST(:g AS uuid)")
            .param("g", genieId).query().listOfRows();
    }

    /** Other tests book the demo Genie's normal hours: put them back. */
    @AfterEach
    void restoreHours() {
        jdbc.sql("DELETE FROM genie_availability WHERE genie_id = CAST(:g AS uuid)").param("g", genieId).update();
        for (Map<String, Object> h : savedHours) {
            jdbc.sql("INSERT INTO genie_availability (genie_id, day_of_week, start_time, end_time) VALUES (CAST(:g AS uuid), :d, :s, :e)")
                .param("g", genieId).param("d", h.get("day_of_week")).param("s", h.get("start_time")).param("e", h.get("end_time"))
                .update();
        }
        jdbc.sql("DELETE FROM genie_time_off WHERE genie_id = CAST(:g AS uuid)").param("g", genieId).update();
    }

    @Test
    void unservedPlacesAndEmptyPagesAreCountedOnceWithoutPersonalData() {
        int before = count("PINCODE_UNSERVED", "400001");
        assertThat(customer.get("/api/v1/serviceability?pincode=400001").body().get("serviceable").asBoolean()).isFalse();
        customer.get("/api/v1/serviceability?pincode=400001"); // the same person again: not counted twice
        assertThat(count("PINCODE_UNSERVED", "400001")).isEqualTo(before + 1);
        assertThat(new ApiClient(port).get("/api/v1/serviceability?pincode=380009").body().get("serviceable").asBoolean()).isTrue();

        int places = count("LOCATION_UNSERVED", null);
        customer.get("/api/v1/serviceability/location?lat=19.076&lng=72.8777"); // Mumbai: in India, no live city
        customer.get("/api/v1/serviceability/location?lat=40.71&lng=-74.0");   // outside India: not demand
        assertThat(count("LOCATION_UNSERVED", null)).isEqualTo(places + 1);

        // a service nobody offers yet: its page is empty, counted with the customer's PIN code (not who it was)
        long lonely = jdbc.sql("INSERT INTO services (category_id, slug, name, base_price, duration_minutes) SELECT category_id, 'it-lonely-' || floor(random() * 1e9), 'Nobody offers this', 299, 30 FROM services WHERE id = :s RETURNING id")
            .param("s", serviceId).query(Long.class).single();
        assertThat(customer.get("/api/v1/genies?city=ahmedabad&serviceId=" + lonely).body().size()).isZero();
        String pincode = jdbc.sql("SELECT pincode FROM demand_events WHERE kind = 'NO_GENIE_LISTED' AND service_id = :s")
            .param("s", lonely).query(String.class).single();
        assertThat(pincode).isNotBlank();
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.columns WHERE table_name = 'demand_events' AND column_name IN ('user_id', 'ip', 'customer_id')")
            .query(Integer.class).single()).as("no personal data columns").isZero();
    }

    @Test
    void genieNowSaysWhyNobodyWasFound() {
        assumeThat(LocalTime.now(INDIA)).isBefore(LocalTime.of(21, 0));
        String quote = "/api/v1/instant/quote?serviceId=" + serviceId + "&addressId=" + addressId;
        workAllDay();

        genie.post("/api/v1/genie/status", "{\"online\":false}");
        assertThat(customer.get(quote).body().get("noGenieReason").asString()).isEqualTo("NONE_ONLINE");
        assertThat(customer.get(quote).body().get("reason").asString()).contains("online");

        genie.post("/api/v1/genie/status", "{\"online\":true}");
        workUntil(LocalTime.now(INDIA).plusMinutes(20));
        assertThat(customer.get(quote).body().get("noGenieReason").asString()).isEqualTo("OUTSIDE_HOURS");

        workAllDay();
        jdbc.sql("INSERT INTO genie_time_off (genie_id, starts_at, ends_at, reason) VALUES (CAST(:g AS uuid), now(), now() + interval '1 hour', 'IT')")
            .param("g", genieId).update();
        assertThat(customer.get(quote).body().get("noGenieReason").asString()).isEqualTo("ALL_BUSY");
        jdbc.sql("DELETE FROM genie_time_off WHERE genie_id = CAST(:g AS uuid)").param("g", genieId).update();

        // the reason is kept on the unmet-demand event too
        assertThat(jdbc.sql("SELECT count(*) FROM demand_events WHERE kind = 'INSTANT_NONE_FREE' AND reason = 'ALL_BUSY'")
            .query(Integer.class).single()).isPositive();

        // a declined search ends with NOBODY_ACCEPTED
        assertThat(customer.get(quote).body().get("available").asBoolean()).isTrue();
        JsonNode search = customer.post("/api/v1/instant/requests", body()).body();
        long offer = genie.get("/api/v1/genie/instant-offers").body().get(0).get("id").asLong();
        genie.post("/api/v1/genie/instant-offers/" + offer + "/decline", null);
        JsonNode ended = customer.get("/api/v1/instant/requests/" + search.get("id").asString()).body();
        assertThat(ended.get("noGenieReason").asString()).isEqualTo("NOBODY_ACCEPTED");
        assertThat(ended.get("message").asString()).contains("couldn't take it").contains("time slot");
    }

    @Test
    void theWorkingHoursCheckUsesTheGeniesRealTravelTime() {
        assumeThat(LocalTime.now(INDIA)).isBefore(LocalTime.of(21, 0));
        genie.post("/api/v1/genie/status", "{\"online\":true}");
        double km = jdbc.sql("SELECT ST_Distance(gp.base_location, a.location) / 1000 FROM genie_profiles gp, addresses a WHERE gp.user_id = CAST(:g AS uuid) AND a.id = CAST(:a AS uuid)")
            .param("g", genieId).param("a", addressId).query(Double.class).single();
        int travel = Math.max(15, (int) Math.ceil(km * 1.3 / 22 * 60) + 10);
        int duration = jdbc.sql("SELECT duration_minutes FROM services WHERE id = :s").param("s", serviceId).query(Integer.class).single();
        assumeThat(travel).as("the demo Genie is near the demo address").isLessThan(55);
        // the day ends a little after travel + job: enough now (a flat hour would not have been)
        workUntil(LocalTime.now(INDIA).plusMinutes(travel + duration + 3));

        JsonNode q = customer.get("/api/v1/instant/quote?serviceId=" + serviceId + "&addressId=" + addressId).body();
        assertThat(q.get("available").asBoolean()).as(q.toString()).isTrue();
        JsonNode search = customer.post("/api/v1/instant/requests", body()).body();
        JsonNode offer = genie.get("/api/v1/genie/instant-offers").body().get(0);
        assertThat(offer.get("travelMinutes").asInt()).isEqualTo(travel);
        JsonNode booking = genie.post("/api/v1/genie/instant-offers/" + offer.get("id").asLong() + "/accept", null).body();
        long blocked = java.time.Duration.between(java.time.OffsetDateTime.parse(booking.get("slotStart").asString()),
            java.time.OffsetDateTime.parse(booking.get("slotEnd").asString())).toMinutes();
        assertThat(blocked).as("blocks travel + job, not a flat hour").isEqualTo(travel + duration);
        admin.post("/api/v1/admin/bookings/" + booking.get("id").asString() + "/cancel", "{\"reason\":\"IT\",\"waiveFee\":true}");
        assertThat(search.get("status").asString()).isEqualTo("SEARCHING");
    }

    @Test
    void theAdminHeatmapGroupsBookingsAndUnmetDemandByPinCode() {
        customer.get("/api/v1/serviceability/location?lat=21.1702&lng=72.8311"); // Surat
        JsonNode unmet = admin.get("/api/v1/admin/demand?layer=UNMET&days=7").body();
        assertThat(unmet.get("total").asInt()).isPositive();
        assertThat(unmet.get("byKind").has("LOCATION_UNSERVED")).isTrue();
        JsonNode surat = null;
        for (JsonNode c : unmet.get("cells")) {
            if (c.get("lat").isNumber() && Math.abs(c.get("lat").asDouble() - 21.17) < 0.01) {
                surat = c;
            }
        }
        assertThat(surat).as("a cell around the Surat pin").isNotNull();
        assertThat(surat.get("kinds").get("LOCATION_UNSERVED").asInt()).isPositive();

        JsonNode bookings = admin.get("/api/v1/admin/demand?layer=BOOKINGS&days=90").body();
        assertThat(bookings.get("total").asInt()).isPositive();
        JsonNode first = bookings.get("cells").get(0);
        assertThat(first.get("pincode").asString()).matches("\\d{6}");
        assertThat(first.get("lat").isNumber()).isTrue();
        assertThat(bookings.get("byHour").size()).isEqualTo(24);
        assertThat(bookings.get("byWeekday").size()).isEqualTo(7);

        int hour = ZonedDateTime.now(INDIA).getHour();
        int now = admin.get("/api/v1/admin/demand?layer=UNMET&days=7&hour=" + hour).body().get("total").asInt();
        assertThat(now).as("the Surat pin was asked this hour").isPositive();
        assertThat(admin.get("/api/v1/admin/demand?days=10").body().get("code").asString()).isEqualTo("INVALID_PERIOD");
        assertThat(customer.get("/api/v1/admin/demand").status()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ helpers

    private int count(String kind, String pincode) {
        return jdbc.sql("SELECT count(*) FROM demand_events WHERE kind = :k AND (CAST(:p AS text) IS NULL OR pincode = :p)")
            .param("k", kind).param("p", pincode).query(Integer.class).single();
    }

    private void workAllDay() {
        workUntil(LocalTime.of(23, 59));
    }

    private void workUntil(LocalTime end) {
        jdbc.sql("DELETE FROM genie_availability WHERE genie_id = CAST(:g AS uuid)").param("g", genieId).update();
        jdbc.sql("INSERT INTO genie_availability (genie_id, day_of_week, start_time, end_time) VALUES (CAST(:g AS uuid), :d, '00:00', :e)")
            .param("g", genieId).param("d", ZonedDateTime.now(INDIA).getDayOfWeek().getValue()).param("e", end.withNano(0)).update();
    }

    private String body() {
        return """
            {"serviceId":%d,"addressId":"%s","paymentMethod":"CASH","tipAmount":0}""".formatted(serviceId, addressId);
    }
}
