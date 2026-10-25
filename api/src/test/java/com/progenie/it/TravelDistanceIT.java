package com.progenie.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * Road distance (2B-7) through HTTP, with a stand-in OSRM server: the price estimate charges the road distance and
 * the booking made from it charges the same, without asking OSRM a second time.
 */
class TravelDistanceIT extends IntegrationTest {

    private static final HttpServer OSRM;
    private static final AtomicInteger ROUTES = new AtomicInteger();

    static {
        try {
            OSRM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        OSRM.createContext("/route/v1/driving/", ex -> {
            ROUTES.incrementAndGet();
            byte[] body = "{\"code\":\"Ok\",\"routes\":[{\"distance\":25000.0,\"duration\":2400.0}],\"waypoints\":[]}"
                .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        OSRM.start();
    }

    @DynamicPropertySource
    static void router(DynamicPropertyRegistry registry) {
        registry.add("progenie.distance.provider", () -> "osrm");
        registry.add("progenie.distance.osrm-url", () -> "http://127.0.0.1:" + OSRM.getAddress().getPort());
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void theEstimateAndTheBookingChargeTheRoadDistance() {
        ApiClient customer = new ApiClient(port).login("customer@example.com");
        JsonNode genieProfile = new ApiClient(port).login("genie@example.com").get("/api/v1/genie/profile").body();
        String genieId = genieProfile.get("id").asString();
        long serviceId = genieProfile.get("services").get(0).get("serviceId").asLong();
        JsonNode address = customer.get("/api/v1/me/addresses").body().get(0);

        JsonNode quote = new ApiClient(port).get("/api/v1/pricing/estimate?genieId=" + genieId + "&serviceId=" + serviceId
            + "&lat=" + address.get("lat").asDouble() + "&lng=" + address.get("lng").asDouble()).body();
        assertThat(quote.get("distanceMethod").asString()).isEqualTo("ROAD");
        assertThat(quote.get("distanceKm").decimalValue()).isEqualByComparingTo("25.00");
        int asked = ROUTES.get();

        String slot = null;
        for (JsonNode day : customer.get("/api/v1/genies/" + genieId + "/slot-days?serviceId=" + serviceId + "&days=7").body()) {
            if (slot == null && day.get("available").asInt() > 0) {
                slot = customer.get("/api/v1/genies/" + genieId + "/slots?serviceId=" + serviceId + "&date="
                    + day.get("date").asString()).body().get("slots").get(0).get("start").asString();
            }
        }
        assertThat(slot).as("a free slot").isNotNull();
        ApiClient.Response booked = customer.post("/api/v1/bookings", """
            {"genieId":"%s","serviceId":%d,"addressId":"%s","slotStart":"%s","paymentMethod":"CASH","tipAmount":0}"""
            .formatted(genieId, serviceId, address.get("id").asString(), slot));
        assertThat(booked.status()).as(String.valueOf(booked.body())).isEqualTo(201);
        String bookingId = booked.body().get("id").asString();
        try {
            JsonNode booking = customer.get("/api/v1/bookings/" + bookingId).body();
            assertThat(booking.get("distanceKm").decimalValue()).isEqualByComparingTo("25.00");
            assertThat(booking.get("travelFee").decimalValue()).isEqualByComparingTo(quote.get("travelFee").decimalValue());
            assertThat(ROUTES.get()).as("the booking reused the cached route").isEqualTo(asked);
        } finally {
            customer.post("/api/v1/bookings/" + bookingId + "/cancel", "{\"reason\":\"integration test\"}");
        }
    }
}
