package com.progenie.it;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3.5A: booking for someone else (the contact gets a code and a tracking link), Genies rating customers right
 * after the job, and photos in reviews (public, reportable, hidden by admins). "Book again" uses the booking's
 * address id, checked here too.
 */
class Phase35AIT extends IntegrationTest {

    /** The smallest thing that passes the API's file check: the JPEG magic bytes. */
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    ApiClient customer;
    ApiClient genie;
    ApiClient admin;
    String customerId;
    String genieId;
    long serviceId;
    final List<String> createdAddresses = new ArrayList<>();

    @BeforeEach
    void setUp() {
        customer = new ApiClient(port).login("customer@example.com");
        genie = new ApiClient(port).login("genie@example.com");
        admin = new ApiClient(port).login("admin@progenie.in");
        customerId = customer.get("/api/v1/me").body().get("id").asString();
        JsonNode me = genie.get("/api/v1/genie/profile").body();
        genieId = me.get("id").asString();
        serviceId = me.get("services").get(0).get("serviceId").asLong();
        jdbc.sql("UPDATE bookings SET cancellation_fee_status = 'WAIVED' WHERE cancellation_fee_status = 'DUE' AND customer_id = CAST(:c AS uuid)")
            .param("c", customerId).update();
        jdbc.sql("DELETE FROM customer_ratings WHERE customer_id = CAST(:c AS uuid)").param("c", customerId).update();
    }

    @AfterEach
    void cleanUp() {
        createdAddresses.forEach(id -> customer.delete("/api/v1/me/addresses/" + id));
        jdbc.sql("DELETE FROM customer_ratings WHERE customer_id = CAST(:c AS uuid)").param("c", customerId).update();
    }

    @Test
    void thePersonAtTheAddressGetsTheirOwnCodeAndATrackingLink() {
        JsonNode home = customer.get("/api/v1/me/addresses").body().get(0);
        // half a contact is refused; a full one is stored as 10 digits
        assertThat(customer.post("/api/v1/me/addresses", address(home, "Asha Shah", null)).body().get("code").asString())
            .isEqualTo("CONTACT_INCOMPLETE");
        JsonNode parents = customer.post("/api/v1/me/addresses", address(home, "Asha Shah", "+91 98250 12345")).body();
        createdAddresses.add(parents.get("id").asString());
        assertThat(parents.get("contactName").asString()).isEqualTo("Asha Shah");
        assertThat(parents.get("contactPhone").asString()).isEqualTo("9825012345");

        String id = bookAndAccept(parents.get("id").asString());
        JsonNode mine = customer.get("/api/v1/bookings/" + id).body();
        assertThat(mine.get("addressId").asString()).as("book again reuses the address").isEqualTo(parents.get("id").asString());
        assertThat(mine.get("address").get("contactName").asString()).isEqualTo("Asha Shah");
        JsonNode job = genie.get("/api/v1/genie/bookings/" + id).body();
        assertThat(job.get("address").get("contactPhone").asString()).as("the Genie calls the person there").isEqualTo("9825012345");
        assertThat(job.get("customer").get("phone").isNull()).as("instead of the customer").isTrue();
        assertThat(job.get("addressId").isNull()).isTrue();

        moveSlotToSoon(id, 50);
        assertThat(genie.post("/api/v1/genie/bookings/" + id + "/travel", null).status()).isEqualTo(200);
        String token = jdbc.sql("SELECT contact_track_token FROM bookings WHERE id = CAST(:b AS uuid)").param("b", id)
            .query(String.class).single();
        assertThat(token).hasSizeGreaterThan(40);
        List<String> sent = jdbc.sql("SELECT channel FROM outbound_messages WHERE template = 'BOOKING_ON_THE_WAY_CONTACT' AND destination = '9825012345' AND user_id IS NULL AND sensitive AND dedupe_key LIKE :k ORDER BY channel")
            .param("k", "booking:" + id + ":%").query(String.class).list();
        assertThat(sent).containsExactly("SMS", "WHATSAPP");

        ApiClient anyone = new ApiClient(port);
        JsonNode track = anyone.get("/api/v1/track/" + token).body();
        assertThat(track.get("status").asString()).isEqualTo("ON_THE_WAY");
        assertThat(track.get("genieName").asString()).isNotBlank().doesNotContain(" ");
        assertThat(anyone.get("/api/v1/track/" + token + "x").body().get("code").asString()).isEqualTo("LINK_EXPIRED");

        // the contact's code starts the job; the customer never had to show theirs
        moveSlotToSoon(id, 5);
        jdbc.sql("UPDATE bookings SET contact_start_otp_hash = encode(sha256(CAST(id::text || ':4321' AS bytea)), 'hex') WHERE id = CAST(:b AS uuid)")
            .param("b", id).update();
        assertThat(genie.post("/api/v1/genie/bookings/" + id + "/start", "{\"code\":\"4321\"}").body().get("status").asString())
            .isEqualTo("IN_PROGRESS");
        assertThat(anyone.get("/api/v1/track/" + token).body().get("status").asString()).isEqualTo("IN_PROGRESS");
        genie.post("/api/v1/genie/bookings/" + id + "/complete", "{\"cashCollected\":true}");
        assertThat(anyone.get("/api/v1/track/" + token).status()).as("the link ends with the job").isEqualTo(404);
    }

    @Test
    void genieRatesTheCustomerRightAfterTheJobAndAdminsSeeTheFlag() {
        String id = completedBooking();
        JsonNode job = genie.get("/api/v1/genie/bookings/" + id).body();
        assertThat(texts(job.get("allowedActions"))).contains("RATE_CUSTOMER");
        assertThat(genie.post("/api/v1/genie/bookings/" + id + "/customer-rating", "{\"rating\":5,\"tags\":[\"GREAT\",\"RUDE\"]}")
            .body().get("code").asString()).isEqualTo("CONFLICTING_TAGS");
        ApiClient.Response rated = genie.post("/api/v1/genie/bookings/" + id + "/customer-rating",
            "{\"rating\":2,\"tags\":[\"NOT_AT_HOME\"]}");
        assertThat(rated.status()).isEqualTo(201);
        assertThat(genie.post("/api/v1/genie/bookings/" + id + "/customer-rating", "{\"rating\":3}").body().get("code").asString())
            .isEqualTo("ALREADY_RATED");
        job = genie.get("/api/v1/genie/bookings/" + id).body();
        assertThat(job.get("customerRating").get("rating").asInt()).isEqualTo(2);
        assertThat(texts(job.get("allowedActions"))).doesNotContain("RATE_CUSTOMER");
        assertThat(job.get("customer").get("avgRating").isNull()).as("Genies see it from 3 ratings").isTrue();
        JsonNode mine = customer.get("/api/v1/bookings/" + id).body();
        assertThat(mine.get("customerRating").isNull()).as("never shown to the customer").isTrue();
        assertThat(mine.get("customer").get("avgRating").isNull()).isTrue();

        // only right after the job
        String late = completedBooking();
        jdbc.sql("UPDATE bookings SET completed_at = now() - interval '2 hours' WHERE id = CAST(:b AS uuid)").param("b", late).update();
        assertThat(genie.post("/api/v1/genie/bookings/" + late + "/customer-rating", "{\"rating\":4}").body().get("code").asString())
            .isEqualTo("RATING_WINDOW_CLOSED");

        // a second "not at home" in 90 days flags the customer for admin; from 3 ratings Genies see the average
        rateByDatabase(late, 1, "{UNSAFE}");
        rateByDatabase(completedBooking(), 4, "{}");
        JsonNode users = admin.get("/api/v1/admin/users?role=CUSTOMER&flagged=true&q=customer%40example.com").body().get("items");
        assertThat(users.size()).isEqualTo(1);
        assertThat(users.get(0).get("flagReason").asString()).contains("\"not at home\" or \"unsafe\"");
        assertThat(users.get(0).get("customerRatingCount").asInt()).isEqualTo(3);
        JsonNode adminView = admin.get("/api/v1/admin/bookings/" + id).body();
        assertThat(adminView.get("customer").get("flagReason").asString()).isNotBlank();
        job = genie.get("/api/v1/genie/bookings/" + id).body();
        assertThat(job.get("customer").get("avgRating").decimalValue()).isEqualByComparingTo("2.3");
        assertThat(job.get("customer").get("flagReason").isNull()).as("the flag is for admins").isTrue();
        JsonNode past = genie.get("/api/v1/genie/bookings?scope=PAST&size=50").body().get("items");
        assertThat(past.get(0).get("customerRatingCount").asInt()).isEqualTo(3);
        JsonNode customerList = customer.get("/api/v1/bookings?scope=PAST&size=5").body().get("items");
        assertThat(customerList.get(0).get("customerRating").isNull()).isTrue();
    }

    @Test
    void reviewPhotosArePublicReportableAndHiddenByAdmins() {
        String id = completedBooking();
        assertThat(customer.upload("/api/v1/bookings/" + id + "/review/photos", "file", "a.jpg", "image/jpeg", JPEG)
            .body().get("code").asString()).isEqualTo("REVIEW_FIRST");
        String reviewId = customer.post("/api/v1/bookings/" + id + "/review", "{\"rating\":5,\"comment\":\"Neat work\"}")
            .body().get("id").asString();
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            JsonNode photo = customer.upload("/api/v1/bookings/" + id + "/review/photos", "file", "a.jpg", "image/jpeg", JPEG).body();
            urls.add(photo.get("url").asString());
        }
        assertThat(urls.get(0)).startsWith("/api/v1/review-photos/");
        assertThat(customer.upload("/api/v1/bookings/" + id + "/review/photos", "file", "a.jpg", "image/jpeg", JPEG)
            .body().get("code").asString()).isEqualTo("TOO_MANY_PHOTOS");
        assertThat(customer.upload("/api/v1/bookings/" + id + "/review/photos", "file", "a.txt", "text/plain", "hello".getBytes())
            .status()).isIn(400, 422);

        ApiClient anyone = new ApiClient(port);
        assertThat(anyone.open(urls.get(0)).status()).as("a short-lived link to the file").isEqualTo(302);
        assertThat(photoUrlsOnProfile(anyone, reviewId)).containsAll(urls);

        // the customer removes one; the Genie reports another
        String removed = urls.remove(2);
        assertThat(customer.delete("/api/v1/bookings/" + id + "/review/photos/" + removed.substring(removed.lastIndexOf('/') + 1)).status())
            .isEqualTo(204);
        String reported = urls.get(0).substring(urls.get(0).lastIndexOf('/') + 1);
        assertThat(genie.post("/api/v1/genie/reviews/" + reviewId + "/photos/" + reported + "/report", "{\"reason\":\"Not my work\"}")
            .status()).isEqualTo(204);
        assertThat(reportedFlag(genie.get("/api/v1/genie/reviews?size=50").body(), reported)).isTrue();
        assertThat(reportedFlag(anyone.get("/api/v1/genies/" + genieId + "/reviews?size=50").body(), reported))
            .as("only the Genie sees what they reported").isFalse();

        JsonNode queue = admin.get("/api/v1/admin/review-photos?reported=true&size=50").body().get("items");
        JsonNode item = null;
        for (JsonNode q : queue) {
            if (q.get("id").asString().equals(reported)) {
                item = q;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.get("reportReason").asString()).isEqualTo("Not my work");
        assertThat(admin.post("/api/v1/admin/review-photos/" + reported + "/hide", null).status()).isEqualTo(204);
        assertThat(anyone.open(urls.get(0)).status()).isEqualTo(404);
        assertThat(photoUrlsOnProfile(anyone, reviewId)).containsExactly(urls.get(1));
        assertThat(admin.post("/api/v1/admin/review-photos/" + reported + "/keep", null).status()).isEqualTo(204);
        assertThat(anyone.open(urls.get(0)).status()).isEqualTo(302);
    }

    // ------------------------------------------------------------------ helpers

    private static String address(JsonNode home, String contactName, String contactPhone) {
        return """
            {"label":"Other","line1":"Parents' flat, 3 Gulmohar Park","pincode":"%s","lat":%s,"lng":%s,"isDefault":false,
             "contactName":%s,"contactPhone":%s}""".formatted(home.get("pincode").asString(), home.get("lat").asDouble(),
            home.get("lng").asDouble(), contactName == null ? "null" : "\"" + contactName + "\"",
            contactPhone == null ? "null" : "\"" + contactPhone + "\"");
    }

    private String completedBooking() {
        String id = bookAndAccept(customer.get("/api/v1/me/addresses").body().get(0).get("id").asString());
        jdbc.sql("UPDATE bookings SET status = 'IN_PROGRESS', started_at = now() WHERE id = CAST(:b AS uuid)").param("b", id).update();
        assertThat(genie.post("/api/v1/genie/bookings/" + id + "/complete", "{\"cashCollected\":true}").status()).isEqualTo(200);
        return id;
    }

    private void rateByDatabase(String bookingId, int rating, String tags) {
        jdbc.sql("INSERT INTO customer_ratings (booking_id, customer_id, genie_id, rating, tags) SELECT id, customer_id, genie_id, :r, CAST(:t AS text[]) FROM bookings WHERE id = CAST(:b AS uuid)")
            .param("b", bookingId).param("r", rating).param("t", tags).update();
    }

    private List<String> photoUrlsOnProfile(ApiClient anyone, String reviewId) {
        String comment = jdbc.sql("SELECT comment FROM reviews WHERE id = CAST(:r AS uuid)").param("r", reviewId)
            .query(String.class).single();
        for (JsonNode r : anyone.get("/api/v1/genies/" + genieId).body().get("reviews")) {
            if (comment.equals(r.get("comment").asString())) {
                return texts(r.get("photoUrls"));
            }
        }
        return List.of();
    }

    private static boolean reportedFlag(JsonNode page, String photoId) {
        for (JsonNode r : page.get("items")) {
            for (JsonNode p : r.get("photos")) {
                if (p.get("id").asString().equals(photoId)) {
                    return p.get("reported").asBoolean();
                }
            }
        }
        throw new AssertionError("photo " + photoId + " not listed");
    }

    /** Moves the slot to {@code minutes} from now (clearing anything of this Genie's in that window). */
    private void moveSlotToSoon(String id, int minutes) {
        jdbc.sql("UPDATE bookings o SET status = 'CANCELLED', cancelled_at = now() FROM bookings b WHERE b.id = CAST(:b AS uuid) AND o.genie_id = b.genie_id AND o.id <> b.id AND o.status IN ('REQUESTED', 'ACCEPTED', 'ON_THE_WAY', 'IN_PROGRESS') AND tstzrange(o.slot_start, o.slot_end) && tstzrange(now() + make_interval(mins => :m), now() + make_interval(mins => :m + 30))")
            .param("m", minutes).param("b", id).update();
        jdbc.sql("UPDATE bookings SET slot_start = now() + make_interval(mins => :m), slot_end = now() + make_interval(mins => :m + 30) WHERE id = CAST(:b AS uuid)")
            .param("m", minutes).param("b", id).update();
    }

    private String bookAndAccept(String addressId) {
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

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asString()));
        return out;
    }
}
