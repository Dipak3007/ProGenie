package com.progenie.it;

import java.time.LocalTime;
import java.time.ZoneId;

import com.progenie.booking.InstantBookings;
import com.progenie.provider.GeniePresence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * Phase 3C-9 Genie Now over HTTP: quote, search, offer, accept / decline / cancel / time-out, the instant fee, and
 * the automatic offline switch. One Genie (genie@example.com) is the only one online, so it is always the one asked.
 */
class Phase3C9IT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    InstantBookings instant;

    @Autowired
    GeniePresence presence;

    ApiClient customer;
    ApiClient genie;
    ApiClient admin;
    long serviceId;
    String addressId;

    @BeforeEach
    void setUp() {
        // the job (arrival window + duration) must end before midnight in India for anyone to be "within working hours"
        assumeThat(LocalTime.now(ZoneId.of("Asia/Kolkata"))).isBefore(LocalTime.of(21, 30));
        customer = new ApiClient(port).login("customer@example.com");
        genie = new ApiClient(port).login("genie@example.com");
        admin = new ApiClient(port).login("admin@progenie.in");
        JsonNode me = genie.get("/api/v1/genie/profile").body();
        String genieId = me.get("id").asString();
        serviceId = me.get("services").get(0).get("serviceId").asLong();
        addressId = customer.get("/api/v1/me/addresses").body().get(0).get("id").asString();

        // A clean slate: only this Genie online, working all day, nothing booked soon, no open searches or fees
        jdbc.sql("UPDATE genie_profiles SET is_online = FALSE WHERE user_id <> CAST(:g AS uuid)").param("g", genieId).update();
        jdbc.sql("DELETE FROM genie_availability WHERE genie_id = CAST(:g AS uuid) AND start_time = '00:00' AND end_time = '23:59'")
            .param("g", genieId).update();
        jdbc.sql("INSERT INTO genie_availability (genie_id, day_of_week, start_time, end_time) VALUES (CAST(:g AS uuid), :d, '00:00', '23:59')")
            .param("g", genieId).param("d", java.time.LocalDate.now(ZoneId.of("Asia/Kolkata")).getDayOfWeek().getValue()).update();
        jdbc.sql("UPDATE bookings SET status = 'CANCELLED', cancelled_at = now() WHERE genie_id = CAST(:g AS uuid) AND status IN ('REQUESTED', 'ACCEPTED', 'ON_THE_WAY', 'IN_PROGRESS') AND slot_start < now() + interval '4 hours' AND slot_end > now()")
            .param("g", genieId).update();
        jdbc.sql("UPDATE instant_requests SET status = 'CANCELLED', ended_at = now() WHERE status = 'SEARCHING'").update();
        jdbc.sql("UPDATE instant_offers SET status = 'WITHDRAWN' WHERE status = 'OFFERED'").update();
        jdbc.sql("UPDATE bookings SET cancellation_fee_status = 'WAIVED' WHERE cancellation_fee_status = 'DUE' AND customer_id = (SELECT id FROM users WHERE email = 'customer@example.com')").update();
        jdbc.sql("UPDATE services SET instant_enabled = TRUE WHERE id = :s").param("s", serviceId).update();
        assertThat(genie.post("/api/v1/genie/status", "{\"online\":true}").status()).isEqualTo(200);
    }

    @Test
    void theNearestFreeGenieAcceptsAndTheBookingCarriesTheInstantFee() {
        // admin sets a ₹25 instant fee for the city
        JsonNode city = admin.get("/api/v1/admin/cities").body().get(0);
        String pricing = """
            {"commissionRate":%s,"travelFreeKm":%s,"travelBaseFee":%s,"travelPerKm":%s,"travelFeeCap":%s,"instantFee":25}"""
            .formatted(city.get("commissionRate").asString(), city.get("travelFreeKm").asString(), city.get("travelBaseFee").asString(),
                city.get("travelPerKm").asString(), city.get("travelFeeCap").asString());
        ApiClient.Response priced = admin.put("/api/v1/admin/cities/" + city.get("cityId").asLong() + "/pricing", pricing);
        assertThat(priced.status()).as(String.valueOf(priced.body())).isEqualTo(200);
        assertThat(priced.body().get("instantFee").decimalValue()).isEqualByComparingTo("25");

        JsonNode quote = customer.get("/api/v1/instant/quote?serviceId=" + serviceId + "&addressId=" + addressId).body();
        assertThat(quote.get("available").asBoolean()).as(quote.toString()).isTrue();
        assertThat(quote.get("genieCount").asInt()).isEqualTo(1);
        assertThat(quote.get("instantFee").decimalValue()).isEqualByComparingTo("25");
        assertThat(quote.get("priceFrom").decimalValue()).isEqualByComparingTo(quote.get("priceTo").decimalValue());

        JsonNode searching = start();
        assertThat(searching.get("status").asString()).isEqualTo("SEARCHING");
        assertThat(searching.get("geniesAsked").asInt()).isEqualTo(1);
        assertThat(searching.get("offerExpiresAt").isNull()).isFalse();
        assertThat(customer.post("/api/v1/instant/requests", body()).body().get("code").asString()).isEqualTo("INSTANT_IN_PROGRESS");

        JsonNode offers = genie.get("/api/v1/genie/instant-offers").body();
        assertThat(offers.size()).isEqualTo(1);
        JsonNode offer = offers.get(0);
        assertThat(offer.get("earning").decimalValue()).isPositive();
        assertThat(offer.get("serviceName").asString()).isNotBlank();

        ApiClient.Response accepted = genie.post("/api/v1/genie/instant-offers/" + offer.get("id").asLong() + "/accept", null);
        assertThat(accepted.status()).as(String.valueOf(accepted.body())).isEqualTo(200);
        JsonNode booking = accepted.body();
        assertThat(booking.get("status").asString()).isEqualTo("ACCEPTED");
        assertThat(booking.get("instant").asBoolean()).isTrue();
        assertThat(booking.get("instantFee").decimalValue()).isEqualByComparingTo("25");
        assertThat(booking.get("totalAmount").decimalValue()).isEqualByComparingTo(
            booking.get("serviceAmount").decimalValue().add(booking.get("travelFee").decimalValue())
                .add(booking.get("instantFee").decimalValue()).add(booking.get("tipAmount").decimalValue()));
        assertThat(texts(booking.get("allowedActions"))).contains("START_TRAVEL", "START");

        JsonNode done = customer.get("/api/v1/instant/requests/" + searching.get("id").asString()).body();
        assertThat(done.get("status").asString()).isEqualTo("BOOKED");
        assertThat(done.get("bookingId").asString()).isEqualTo(booking.get("id").asString());
        JsonNode mine = customer.get("/api/v1/bookings/" + booking.get("id").asString()).body();
        assertThat(mine.get("instant").asBoolean()).isTrue();
        assertThat(texts(mine.get("allowedActions"))).contains("CANCEL", "START_CODE");
        // a second accept of the same offer is refused
        assertThat(genie.post("/api/v1/genie/instant-offers/" + offer.get("id").asLong() + "/accept", null).status()).isEqualTo(409);

        // the Genie is now busy: nobody else is free
        JsonNode busy = customer.get("/api/v1/instant/quote?serviceId=" + serviceId + "&addressId=" + addressId).body();
        assertThat(busy.get("available").asBoolean()).isFalse();
        assertThat(busy.get("reasonCode").asString()).isEqualTo("NO_GENIE_NEARBY");
        admin.post("/api/v1/admin/bookings/" + booking.get("id").asString() + "/cancel", "{\"reason\":\"IT\",\"waiveFee\":true}");
        admin.put("/api/v1/admin/cities/" + city.get("cityId").asLong() + "/pricing", pricing.replace("\"instantFee\":25", "\"instantFee\":0"));
    }

    @Test
    void whenTheOnlyGenieDeclinesTheSearchEndsWithNoGenie() {
        JsonNode searching = start();
        long offerId = genie.get("/api/v1/genie/instant-offers").body().get(0).get("id").asLong();
        assertThat(genie.post("/api/v1/genie/instant-offers/" + offerId + "/decline", null).status()).isEqualTo(204);
        JsonNode ended = customer.get("/api/v1/instant/requests/" + searching.get("id").asString()).body();
        assertThat(ended.get("status").asString()).isEqualTo("NO_GENIE");
        assertThat(ended.get("message").asString()).contains("Book a time slot");
        assertThat(jdbc.sql("SELECT pincode FROM instant_requests WHERE id = CAST(:id AS uuid)").param("id", searching.get("id").asString())
            .query(String.class).single()).as("kept for the demand heatmap").isNotBlank();
    }

    @Test
    void anUnansweredOfferTimesOutAndACancelledSearchCanNoLongerBeAccepted() {
        JsonNode first = start();
        jdbc.sql("UPDATE instant_offers SET expires_at = now() - interval '10 seconds' WHERE request_id = CAST(:id AS uuid)")
            .param("id", first.get("id").asString()).update();
        instant.sweep();
        assertThat(customer.get("/api/v1/instant/requests/" + first.get("id").asString()).body().get("status").asString())
            .isEqualTo("NO_GENIE");
        assertThat(genie.get("/api/v1/genie/instant-offers").body().size()).isZero();

        JsonNode second = start();
        long offerId = genie.get("/api/v1/genie/instant-offers").body().get(0).get("id").asLong();
        JsonNode cancelled = customer.post("/api/v1/instant/requests/" + second.get("id").asString() + "/cancel", null).body();
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        ApiClient.Response late = genie.post("/api/v1/genie/instant-offers/" + offerId + "/accept", null);
        assertThat(late.status()).isEqualTo(409);
        assertThat(late.body().get("code").asString()).isEqualTo("OFFER_GONE");
    }

    @Test
    void servicesNotSwitchedOnForGenieNowAreRefused() {
        jdbc.sql("UPDATE services SET instant_enabled = FALSE WHERE id = :s").param("s", serviceId).update();
        JsonNode quote = customer.get("/api/v1/instant/quote?serviceId=" + serviceId + "&addressId=" + addressId).body();
        assertThat(quote.get("available").asBoolean()).isFalse();
        assertThat(quote.get("reasonCode").asString()).isEqualTo("INSTANT_NOT_AVAILABLE");
        assertThat(customer.post("/api/v1/instant/requests", body()).status()).isEqualTo(422);
        JsonNode services = admin.get("/api/v1/admin/services").body();
        assertThat(services.get(0).has("instantEnabled")).isTrue();
    }

    @Test
    void aGenieWhoseProGeniePageIsClosedGoesOffline() {
        jdbc.sql("UPDATE genie_profiles SET live_seen_at = now() - interval '5 minutes' WHERE user_id = (SELECT id FROM users WHERE email = 'genie@example.com')").update();
        presence.sweep();
        assertThat(jdbc.sql("SELECT is_online FROM genie_profiles WHERE user_id = (SELECT id FROM users WHERE email = 'genie@example.com')")
            .query(Boolean.class).single()).isFalse();
        JsonNode quote = customer.get("/api/v1/instant/quote?serviceId=" + serviceId + "&addressId=" + addressId).body();
        assertThat(quote.get("available").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private String body() {
        return """
            {"serviceId":%d,"addressId":"%s","paymentMethod":"CASH","tipAmount":0}""".formatted(serviceId, addressId);
    }

    private JsonNode start() {
        ApiClient.Response r = customer.post("/api/v1/instant/requests", body());
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(201);
        return r.body();
    }

    private static java.util.List<String> texts(JsonNode array) {
        java.util.List<String> out = new java.util.ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }
}
