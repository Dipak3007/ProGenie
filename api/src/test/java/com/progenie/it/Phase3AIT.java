package com.progenie.it;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.progenie.booking.BookingPhotoRetentionJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3A: before/after job photos, badges and the monthly earnings statement, end to end over HTTP. */
class Phase3AIT extends IntegrationTest {

    /** The smallest thing that passes the API's file check: the JPEG magic bytes. */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};
    /** A seeded Genie (Hitesh, plumber) whose numbers this test changes and puts back. */
    private static final String PLUMBER = "08b4731d-64bb-52ca-bd5a-e7670b4eb1ad";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    BookingPhotoRetentionJob retention;

    @Test
    void jobPhotosFollowTheJobAndExpireAYearLaterUnlessAComplaintIsOpen() throws Exception {
        ApiClient customer = new ApiClient(port).login("customer@example.com");
        ApiClient genie = new ApiClient(port).login("genie@example.com");
        String bookingId = bookAndAccept(customer, genie);
        String photos = "/api/v1/genie/bookings/" + bookingId + "/photos";

        // not started yet: no photos
        assertThat(genie.upload(photos + "?stage=BEFORE", "file", "a.jpg", "image/jpeg", JPEG).body().get("code").asString())
            .isEqualTo("PHOTOS_CLOSED");
        startByDatabase(bookingId);
        JsonNode detail = genie.get("/api/v1/genie/bookings/" + bookingId).body();
        assertThat(texts(detail.get("allowedActions"))).contains("ADD_PHOTO_BEFORE", "ADD_PHOTO_AFTER");

        for (int i = 0; i < 3; i++) {
            ApiClient.Response added = genie.upload(photos + "?stage=BEFORE", "file", "before" + i + ".jpg", "image/jpeg", JPEG);
            assertThat(added.status()).as(String.valueOf(added.body())).isEqualTo(201);
            assertThat(added.body().get("stage").asString()).isEqualTo("BEFORE");
        }
        ApiClient.Response fourth = genie.upload(photos + "?stage=BEFORE", "file", "x.jpg", "image/jpeg", JPEG);
        assertThat(fourth.status()).isEqualTo(422);
        assertThat(fourth.body().get("code").asString()).isEqualTo("TOO_MANY_PHOTOS");
        ApiClient.Response text = genie.upload(photos + "?stage=AFTER", "file", "notes.jpg", "image/jpeg",
            "not a photo".getBytes(StandardCharsets.UTF_8));
        assertThat(text.body().get("code").asString()).isEqualTo("UNSUPPORTED_FILE");
        String afterId = genie.upload(photos + "?stage=AFTER", "file", "after.jpg", "image/jpeg", JPEG).body().get("id").asString();
        ApiClient.Response extra = genie.upload(photos + "?stage=AFTER", "file", "after2.jpg", "image/jpeg", JPEG);
        assertThat(extra.status()).isEqualTo(201);

        // another Genie and the customer cannot add photos
        assertThat(customer.upload(photos + "?stage=AFTER", "file", "c.jpg", "image/jpeg", JPEG).status()).isEqualTo(403);
        ApiClient other = new ApiClient(port).login("9909000001");
        assertThat(other.upload(photos + "?stage=AFTER", "file", "o.jpg", "image/jpeg", JPEG).status()).isEqualTo(404);

        // the customer sees them, and the short-lived link opens the image without logging in
        JsonNode seen = customer.get("/api/v1/bookings/" + bookingId).body().get("photos");
        assertThat(seen).hasSize(5);
        ApiClient.Response image = new ApiClient(port).open(seen.get(0).get("url").asString());
        assertThat(image.status()).isEqualTo(200);
        assertThat(image.raw()).startsWith(JPEG);

        assertThat(genie.delete(photos + "/" + afterId).status()).isEqualTo(204);
        assertThat(genie.post("/api/v1/genie/bookings/" + bookingId + "/complete", "{\"cashCollected\":true}").status()).isEqualTo(200);
        detail = genie.get("/api/v1/genie/bookings/" + bookingId).body();
        assertThat(detail.get("photos")).hasSize(4);
        assertThat(texts(detail.get("allowedActions"))).contains("ADD_PHOTO_AFTER").doesNotContain("ADD_PHOTO_BEFORE");
        assertThat(genie.upload(photos + "?stage=BEFORE", "file", "late.jpg", "image/jpeg", JPEG).body().get("code").asString())
            .isEqualTo("PHOTOS_CLOSED");

        // a complaint shows the job photos
        ApiClient.Response ticket = customer.post("/api/v1/bookings/" + bookingId + "/tickets",
            "{\"category\":\"QUALITY\",\"subject\":\"Tap still drips\",\"description\":\"The tap still drips after the visit.\"}");
        assertThat(ticket.status()).as(String.valueOf(ticket.body())).isEqualTo(201);
        String ticketId = ticket.body().get("id").asString();
        assertThat(customer.get("/api/v1/tickets/" + ticketId).body().get("jobPhotos")).hasSize(4);

        // a year later: kept while the complaint is open, removed (files too) once it is closed
        List<String> keys = jdbc.sql("SELECT object_key FROM booking_photos WHERE booking_id = CAST(:b AS uuid)")
            .param("b", bookingId).query(String.class).list();
        jdbc.sql("UPDATE bookings SET completed_at = now() - interval '400 days' WHERE id = CAST(:b AS uuid)")
            .param("b", bookingId).update();
        retention.run();
        assertThat(photoCount(bookingId)).isEqualTo(4);
        jdbc.sql("UPDATE tickets SET status = 'CLOSED', closed_at = now() WHERE id = CAST(:t AS uuid)").param("t", ticketId).update();
        retention.run();
        assertThat(photoCount(bookingId)).isZero();
        for (String key : keys) {
            assertThat(Files.exists(Path.of("target/it-uploads").resolve(key))).as(key).isFalse();
        }
    }

    @Test
    void badgesAreRecalculatedAndShownOnCardsAndProfiles() {
        ApiClient admin = new ApiClient(port).login("admin@progenie.in");
        record Saved(java.math.BigDecimal avgRating, int ratingCount, int completedJobs, java.time.OffsetDateTime approvedAt) {
        }
        Saved saved = jdbc.sql("SELECT avg_rating, rating_count, completed_jobs, approved_at FROM genie_profiles WHERE user_id = CAST(:g AS uuid)")
            .param("g", PLUMBER).query(Saved.class).single();
        try {
            jdbc.sql("UPDATE genie_profiles SET avg_rating = 4.9, rating_count = 25, completed_jobs = 120, approved_at = now() - interval '5 days' WHERE user_id = CAST(:g AS uuid)")
                .param("g", PLUMBER).update();
            ApiClient.Response run = admin.post("/api/v1/admin/genies/badges/recalculate", null);
            assertThat(run.status()).isEqualTo(200);
            assertThat(run.body().get("genies").asInt()).isPositive();

            JsonNode card = new ApiClient(port).get("/api/v1/genies/" + PLUMBER).body().get("genie");
            assertThat(texts(card.get("badges"))).containsExactly("TOP_GENIE", "JOBS_100", "NEW");

            // Top Genie moves up the default order: first among the plumbers
            JsonNode list = new ApiClient(port).get("/api/v1/genies?category=plumber&sort=RATING").body();
            assertThat(list.get(0).get("id").asString()).isEqualTo(PLUMBER);

            jdbc.sql("UPDATE genie_profiles SET completed_jobs = 510 WHERE user_id = CAST(:g AS uuid)").param("g", PLUMBER).update();
            admin.post("/api/v1/admin/genies/badges/recalculate", null);
            card = new ApiClient(port).get("/api/v1/genies/" + PLUMBER).body().get("genie");
            assertThat(texts(card.get("badges"))).contains("JOBS_500").doesNotContain("JOBS_100");

            // the Genie sees their own badges; only admins can recalculate
            assertThat(new ApiClient(port).login("genie@example.com").post("/api/v1/admin/genies/badges/recalculate", null)
                .status()).isEqualTo(403);
        } finally {
            jdbc.sql("UPDATE genie_profiles SET avg_rating = :r, rating_count = :c, completed_jobs = :j, approved_at = :a WHERE user_id = CAST(:g AS uuid)")
                .param("r", saved.avgRating()).param("c", saved.ratingCount()).param("j", saved.completedJobs())
                .param("a", saved.approvedAt()).param("g", PLUMBER).update();
            admin.post("/api/v1/admin/genies/badges/recalculate", null);
        }
        assertThat(texts(new ApiClient(port).get("/api/v1/genies/" + PLUMBER).body().get("genie").get("badges")))
            .doesNotContain("TOP_GENIE");
    }

    @Test
    void theEarningsStatementAgreesWithTheWallet() {
        ApiClient customer = new ApiClient(port).login("customer@example.com");
        ApiClient genie = new ApiClient(port).login("genie@example.com");
        String bookingId = bookAndAccept(customer, genie);
        startByDatabase(bookingId);
        JsonNode done = genie.post("/api/v1/genie/bookings/" + bookingId + "/complete", "{\"cashCollected\":true}").body();
        String ref = done.get("bookingRef").asString();
        String month = java.time.YearMonth.now(java.time.ZoneId.of("Asia/Kolkata")).toString();

        ApiClient.Response csv = genie.get("/api/v1/genie/statements?month=" + month + "&format=csv");
        assertThat(csv.status()).isEqualTo(200);
        String text = new String(csv.raw(), StandardCharsets.UTF_8);
        assertThat(text).contains("ProGenie earnings statement", "JOBS COMPLETED", ref, "PAYOUTS", "WALLET ENTRIES");
        String balance = genie.get("/api/v1/genie/wallet").body().get("balance").decimalValue()
            .setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        assertThat(text).contains("Closing balance," + balance);

        ApiClient.Response pdf = genie.get("/api/v1/genie/statements?month=" + month + "&format=PDF");
        assertThat(pdf.status()).isEqualTo(200);
        assertThat(new String(pdf.raw(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        // admin can download any Genie's; a Genie cannot use the admin endpoint
        String genieId = genie.get("/api/v1/genie/profile").body().get("id").asString();
        ApiClient admin = new ApiClient(port).login("admin@progenie.in");
        assertThat(admin.get("/api/v1/admin/genies/" + genieId + "/statements?month=" + month + "&format=csv").status())
            .isEqualTo(200);
        assertThat(genie.get("/api/v1/admin/genies/" + genieId + "/statements?month=" + month).status()).isEqualTo(403);
        assertThat(admin.get("/api/v1/admin/genies/" + UUID.randomUUID() + "/statements?month=" + month).status()).isEqualTo(404);

        assertThat(genie.get("/api/v1/genie/statements?month=2026-13").body().get("code").asString()).isEqualTo("INVALID_MONTH");
        assertThat(genie.get("/api/v1/genie/statements?month=2099-01").body().get("code").asString()).isEqualTo("INVALID_MONTH");
        assertThat(genie.get("/api/v1/genie/statements?month=" + month + "&format=xls").body().get("code").asString())
            .isEqualTo("INVALID_FORMAT");
    }

    // ------------------------------------------------------------------ helpers

    private String bookAndAccept(ApiClient customer, ApiClient genie) {
        JsonNode me = genie.get("/api/v1/genie/profile").body();
        String genieId = me.get("id").asString();
        long serviceId = me.get("services").get(0).get("serviceId").asLong();
        String addressId = customer.get("/api/v1/me/addresses").body().get(0).get("id").asString();
        for (JsonNode day : customer.get("/api/v1/genies/" + genieId + "/slot-days?serviceId=" + serviceId + "&days=14").body()) {
            if (day.get("available").asInt() == 0) {
                continue;
            }
            JsonNode slots = customer.get("/api/v1/genies/" + genieId + "/slots?serviceId=" + serviceId + "&date="
                + day.get("date").asString()).body().get("slots");
            for (JsonNode slot : slots) {
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
        throw new IllegalStateException("no free slot for " + genieId);
    }

    /** Starting needs the customer's code at the door, close to the slot; the test skips that step. */
    private void startByDatabase(String bookingId) {
        jdbc.sql("UPDATE bookings SET status = 'IN_PROGRESS', started_at = now() WHERE id = CAST(:b AS uuid)")
            .param("b", bookingId).update();
    }

    private int photoCount(String bookingId) {
        return jdbc.sql("SELECT count(*) FROM booking_photos WHERE booking_id = CAST(:b AS uuid)")
            .param("b", bookingId).query(Integer.class).single();
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }
}
