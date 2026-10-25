package com.progenie.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end through HTTP against the real schema: serviceability, download links (2B-1) and live updates
 * over Server-Sent Events (2B-2).
 */
@TestPropertySource(properties = "progenie.privacy.export-interval=1s")
class ApiFlowIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Test
    void servesPincodesAndRefusesPinsOutsideIndia() {
        ApiClient anon = new ApiClient(port);
        JsonNode served = anon.get("/api/v1/serviceability?pincode=380009").body();
        assertThat(served.get("serviceable").asBoolean()).isTrue();
        assertThat(served.get("citySlug").asString()).isEqualTo("ahmedabad");
        assertThat(anon.get("/api/v1/serviceability?pincode=400001").body().get("serviceable").asBoolean()).isFalse();
        assertThat(anon.get("/api/v1/serviceability/location?lat=24.86&lng=67.01").body().get("inIndia").asBoolean())
            .as("Karachi").isFalse();
        assertThat(anon.get("/api/v1/serviceability/location?lat=23.03&lng=72.56").body().get("serviceable").asBoolean())
            .as("Navrangpura").isTrue();
    }

    @Test
    void dataExportDownloadsFromAShortLivedLinkWithoutLogin() {
        ApiClient customer = new ApiClient(port).login("customer@example.com");
        ApiClient.Response requested = customer.post("/api/v1/me/data-export", null);
        String jobId = requested.status() == 202 ? requested.body().get("id").asString()
            : requested.body().get("jobId").asString(); // a copy from earlier today
        Awaitility.await().atMost(30, TimeUnit.SECONDS).pollInterval(Duration.ofMillis(500)).until(() -> {
            for (JsonNode job : customer.get("/api/v1/me/data-export").body()) {
                if (job.get("id").asString().equals(jobId) && "READY".equals(job.get("status").asString())) {
                    return true;
                }
            }
            return false;
        });
        JsonNode link = customer.get("/api/v1/me/data-export/" + jobId + "/link").body();
        assertThat(link.get("expiresAt").asString()).isNotBlank();

        ApiClient.Response file = new ApiClient(port).open(link.get("url").asString());
        assertThat(file.status()).isEqualTo(200);
        assertThat(new String(file.raw(), 0, 2)).isEqualTo("PK"); // a ZIP

        String url = link.get("url").asString();
        assertThat(new ApiClient(port).open(url.substring(0, url.length() - 4) + "AAAA").status()).isEqualTo(404);
    }

    @Test
    void theGenieSeesANewRequestLiveAndTheCustomerSeesTheAcceptance() throws Exception {
        ApiClient customer = new ApiClient(port).login("customer@example.com");
        ApiClient genie = new ApiClient(port).login("genie@example.com");
        List<String> genieEvents = listen(genie);
        List<String> customerEvents = listen(customer);
        Awaitility.await().atMost(10, TimeUnit.SECONDS).until(() -> genieEvents.contains("ready") && customerEvents.contains("ready"));

        JsonNode me = genie.get("/api/v1/genie/profile").body();
        String genieId = me.get("id").asString();
        long serviceId = me.get("services").get(0).get("serviceId").asLong();
        String slot = null;
        for (JsonNode day : customer.get("/api/v1/genies/" + genieId + "/slot-days?serviceId=" + serviceId + "&days=7").body()) {
            if (day.get("available").asInt() > 0) {
                JsonNode slots = customer.get("/api/v1/genies/" + genieId + "/slots?serviceId=" + serviceId + "&date="
                    + day.get("date").asString()).body().get("slots");
                slot = slots.get(slots.size() - 1).get("start").asString();
            }
        }
        assertThat(slot).as("a free slot").isNotNull();
        String addressId = customer.get("/api/v1/me/addresses").body().get(0).get("id").asString();

        ApiClient.Response booked = customer.post("/api/v1/bookings", """
            {"genieId":"%s","serviceId":%d,"addressId":"%s","slotStart":"%s","paymentMethod":"CASH","tipAmount":0}"""
            .formatted(genieId, serviceId, addressId, slot));
        assertThat(booked.status()).as(String.valueOf(booked.body())).isEqualTo(201);
        String bookingId = booked.body().get("id").asString();

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(genieEvents)
            .anyMatch(e -> e.startsWith("booking ") && e.contains(bookingId) && e.contains("REQUESTED"))
            .anyMatch(e -> e.startsWith("notification ") && e.contains("BOOKING_REQUESTED")));

        assertThat(genie.post("/api/v1/genie/bookings/" + bookingId + "/accept", null).status()).isEqualTo(200);
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> assertThat(customerEvents)
            .anyMatch(e -> e.startsWith("booking ") && e.contains(bookingId) && e.contains("ACCEPTED")));

        customer.post("/api/v1/bookings/" + bookingId + "/cancel", "{\"reason\":\"integration test\"}");
    }

    @Test
    void theStreamNeedsALogin() {
        assertThat(new ApiClient(port).get("/api/v1/stream").status()).isEqualTo(401);
    }

    /** Opens GET /api/v1/stream and collects "event data" lines in the background. */
    private static List<String> listen(ApiClient client) {
        List<String> events = new CopyOnWriteArrayList<>();
        client.http().sendAsync(client.request("/api/v1/stream").header("Accept", "text/event-stream").GET().build(),
                HttpResponse.BodyHandlers.ofLines())
            .thenAccept(res -> {
                String[] event = {"message"};
                res.body().forEach(line -> {
                    if (line.startsWith("event:")) {
                        event[0] = line.substring(6).trim();
                        if (event[0].equals("ready")) {
                            events.add("ready");
                        }
                    } else if (line.startsWith("data:") && !event[0].equals("ready")) {
                        events.add(event[0] + " " + line.substring(5));
                    }
                });
            });
        return events;
    }
}
