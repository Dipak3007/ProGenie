package com.progenie.it;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.progenie.demand.LaunchAlerts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3.5B: "Notify me when ProGenie arrives" (sign up as a guest, one message when the area opens, then the alert is
 * gone) and the help centre (public answers, suggestions for "Report a problem", helpful votes, admin editor).
 */
class Phase35BIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    LaunchAlerts launchAlerts;

    /** Earlier runs on the same database would hit the rate limits (5 sign-ups an hour, one vote a day). */
    @org.junit.jupiter.api.BeforeEach
    void clearLimits() {
        jdbc.sql("DELETE FROM rate_limit_counters WHERE key LIKE 'launch-alert:%' OR key LIKE 'help-vote:%'").update();
    }

    @Test
    void peopleWaitingForAnAreaHearOnceWhenItOpens() {
        ApiClient guest = new ApiClient(port);
        ApiClient admin = new ApiClient(port).login("admin@progenie.in");
        String pin = unservedPincode();
        String phone = "98" + (10_000_000 + ThreadLocalRandom.current().nextInt(89_999_999));

        assertThat(guest.post("/api/v1/launch-alerts", alert(pin, null, null, phone, null, false)).body().get("code").asString())
            .isEqualTo("CONSENT_NEEDED");
        assertThat(guest.post("/api/v1/launch-alerts", alert(pin, null, null, phone, "a@b.in", true)).body().get("code").asString())
            .isEqualTo("CONTACT_NEEDED");
        assertThat(guest.post("/api/v1/launch-alerts", alert("380009", null, null, phone, null, true)).body().get("code").asString())
            .isEqualTo("ALREADY_SERVED");
        assertThat(guest.post("/api/v1/launch-alerts", alert(null, 23.03, 72.56, phone, null, true)).body().get("code").asString())
            .as("a point in a live city").isEqualTo("ALREADY_SERVED");
        assertThat(guest.post("/api/v1/launch-alerts", alert(null, 24.86, 67.01, phone, null, true)).body().get("code").asString())
            .isEqualTo("LOCATION_OUTSIDE_INDIA");

        ApiClient.Response ok = guest.post("/api/v1/launch-alerts", alert(pin, null, null, "+91 " + phone, null, true));
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.body().get("contact").asString()).as("masked").endsWith(phone.substring(6)).doesNotContain(phone.substring(0, 6));
        guest.post("/api/v1/launch-alerts", alert(pin, null, null, phone, null, true)); // the same again: kept once
        guest.post("/api/v1/launch-alerts", alert(pin, null, null, null, "Waiting." + pin + "@Example.in", true));
        guest.post("/api/v1/launch-alerts", alert(null, 18.531, 73.861, null, "point." + pin + "@example.in", true));
        assertThat(alerts(pin)).isEqualTo(2);
        JsonNode waiting = admin.get("/api/v1/admin/demand?layer=UNMET&days=7").body().get("waiting");
        assertThat(waiting.get("total").asInt()).isGreaterThanOrEqualTo(3);

        // a new city with that PIN code: nothing while it's off, everyone told when it goes live
        String slug = "it-launch-" + pin;
        JsonNode city = admin.post("/api/v1/admin/cities", """
            {"slug":"%s","name":"Launch %s","state":"Maharashtra","centerLat":18.52,"centerLng":73.85,"radiusKm":20}"""
            .formatted(slug, pin)).body();
        long cid = city.get("cityId").asLong();
        assertThat(admin.put("/api/v1/admin/cities/" + cid + "/pincodes/" + pin, "{\"area\":\"Kothrud, Pune\",\"active\":true}").status())
            .isEqualTo(200);
        assertThat(alerts(pin)).as("the city is still off").isEqualTo(2);
        assertThat(admin.put("/api/v1/admin/cities/" + cid + "/status", "{\"active\":true}").body().get("active").asBoolean()).isTrue();
        assertThat(alerts(pin)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM launch_alerts WHERE email = :e").param("e", "point." + pin + "@example.in")
            .query(Integer.class).single()).as("the map point inside the new city too").isZero();
        List<String> sent = jdbc.sql("SELECT channel || ' ' || destination FROM outbound_messages WHERE template = 'LAUNCH_ALERT' AND dedupe_key LIKE :k ORDER BY 1")
            .param("k", "launch:" + cid + ":%").query(String.class).list();
        assertThat(sent).containsExactlyInAnyOrder("EMAIL point." + pin + "@example.in", "EMAIL waiting." + pin + "@example.in",
            "SMS " + phone, "WHATSAPP " + phone);
        assertThat(jdbc.sql("SELECT body FROM outbound_messages WHERE template = 'LAUNCH_ALERT' AND channel = 'SMS' AND destination = :d")
            .param("d", phone).query(String.class).single()).contains("Kothrud, Launch " + pin);
        admin.put("/api/v1/admin/cities/" + cid + "/status", "{\"active\":false}"); // leave Ahmedabad the only live city

        // nobody is kept waiting forever
        jdbc.sql("INSERT INTO launch_alerts (pincode, email, consent_at, created_at) VALUES ('799999', 'old@example.in', now(), now() - interval '400 days')").update();
        launchAlerts.deleteOld();
        assertThat(jdbc.sql("SELECT count(*) FROM launch_alerts WHERE email = 'old@example.in'").query(Integer.class).single()).isZero();
    }

    @Test
    void theHelpCentreAnswersSuggestsCountsAndIsEditedByAdmins() {
        ApiClient guest = new ApiClient(port);
        ApiClient admin = new ApiClient(port).login("admin@progenie.in");
        JsonNode all = guest.get("/api/v1/help").body();
        assertThat(all.size()).isGreaterThanOrEqualTo(30);
        assertThat(all.get(0).get("category").asString()).isEqualTo("BOOKING");
        assertThat(all.get(all.size() - 1).get("category").asString()).isEqualTo("GENIES");
        assertThat(texts(all, "slug")).contains("cancel-free", "what-is-genie-now", "become-a-genie");

        JsonNode payment = guest.get("/api/v1/help/suggestions?ticketCategory=PAYMENT").body();
        assertThat(payment.size()).isBetween(1, 3);
        assertThat(guest.get("/api/v1/help/suggestions?ticketCategory=NOPE").status()).isEqualTo(400);

        long id = all.get(0).get("id").asLong();
        int before = helpfulYes(id);
        guest.post("/api/v1/help/" + id + "/vote", "{\"helpful\":true}");
        guest.post("/api/v1/help/" + id + "/vote", "{\"helpful\":true}"); // the same person again: not counted
        assertThat(helpfulYes(id)).isEqualTo(before + 1);

        assertThat(new ApiClient(port).login("customer@example.com").get("/api/v1/admin/help").status()).isEqualTo(403);
        String slug = "it-help-" + ThreadLocalRandom.current().nextInt(1_000_000);
        String body = """
            {"category":"PAYMENTS","slug":"%s","question":"Can I pay in instalments?","answer":"Not yet.","ticketCategories":["PAYMENT"],"position":5,"published":true}"""
            .formatted(slug);
        JsonNode created = admin.post("/api/v1/admin/help", body).body();
        assertThat(created.get("ticketCategories").get(0).asString()).isEqualTo("PAYMENT");
        assertThat(admin.post("/api/v1/admin/help", body).body().get("code").asString()).isEqualTo("HELP_SLUG_TAKEN");
        assertThat(texts(guest.get("/api/v1/help/suggestions?ticketCategory=PAYMENT").body(), "slug"))
            .as("position 5 comes first").startsWith(slug);
        admin.put("/api/v1/admin/help/" + created.get("id").asLong(), body.replace("\"published\":true", "\"published\":false"));
        assertThat(texts(guest.get("/api/v1/help").body(), "slug")).doesNotContain(slug);
        assertThat(admin.get("/api/v1/admin/help").body().size()).isEqualTo(all.size() + 1);
        assertThat(admin.delete("/api/v1/admin/help/" + created.get("id").asLong()).status()).isEqualTo(204);
    }

    // ------------------------------------------------------------------ helpers

    private static String alert(String pincode, Double lat, Double lng, String phone, String email, boolean consent) {
        return """
            {"pincode":%s,"lat":%s,"lng":%s,"phone":%s,"email":%s,"consent":%s}""".formatted(
            q(pincode), lat, lng, q(phone), q(email), consent);
    }

    private static String q(String s) {
        return s == null ? "null" : "\"" + s + "\"";
    }

    private String unservedPincode() {
        while (true) {
            String pin = "41" + (1000 + ThreadLocalRandom.current().nextInt(8999));
            if (jdbc.sql("SELECT count(*) FROM serviceable_pincodes WHERE pincode = :p").param("p", pin).query(Integer.class).single() == 0) {
                return pin;
            }
        }
    }

    private int alerts(String pin) {
        return jdbc.sql("SELECT count(*) FROM launch_alerts WHERE pincode = :p").param("p", pin).query(Integer.class).single();
    }

    private int helpfulYes(long id) {
        return jdbc.sql("SELECT helpful_yes FROM help_articles WHERE id = :id").param("id", id).query(Integer.class).single();
    }

    private static List<String> texts(JsonNode array, String field) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.get(field).asString()));
        return out;
    }
}
