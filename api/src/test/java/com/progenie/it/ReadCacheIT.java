package com.progenie.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Read cache (2B-6), default memory mode: cached reads still show every change at once, because writes evict.
 * {@link RedisCacheIT} runs the same checks with Redis.
 */
class ReadCacheIT extends IntegrationTest {

    @Value("${local.server.port}")
    int port;

    @Test
    void anAdminEditShowsUpAtOnceDespiteTheCache() {
        ApiClient anon = new ApiClient(port);
        ApiClient admin = new ApiClient(port).login("admin@progenie.in");
        JsonNode first = anon.get("/api/v1/categories").body().get(0);
        anon.get("/api/v1/categories");                                  // now cached
        anon.get("/api/v1/categories/" + first.get("slug").asString());

        JsonNode adminCategory = null;
        for (JsonNode c : admin.get("/api/v1/admin/categories").body()) {
            if (c.get("id").asLong() == first.get("id").asLong()) {
                adminCategory = c;
            }
        }
        assertThat(adminCategory).isNotNull();
        String original = adminCategory.get("name").asString();
        boolean onCards = anon.get("/api/v1/genies?city=ahmedabad&limit=50").body().toString().contains(original);  // cached
        ObjectNode edit = ((ObjectNode) adminCategory.deepCopy()).put("name", original + " (edited)");
        try {
            assertThat(admin.put("/api/v1/admin/categories/" + first.get("id").asLong(), edit.toString()).status()).isEqualTo(200);
            assertThat(anon.get("/api/v1/categories").body().get(0).get("name").asString()).isEqualTo(original + " (edited)");
            assertThat(anon.get("/api/v1/categories/" + first.get("slug").asString()).body().get("category").get("name").asString())
                .isEqualTo(original + " (edited)");
            if (onCards) {
                assertThat(anon.get("/api/v1/genies?city=ahmedabad&limit=50").body().toString())
                    .as("Genie cards carry category names: that cache is emptied too").contains(original + " (edited)");
            }
        } finally {
            admin.put("/api/v1/admin/categories/" + first.get("id").asLong(), edit.put("name", original).toString());
        }
        assertThat(anon.get("/api/v1/categories").body().get(0).get("name").asString()).isEqualTo(original);
    }

    @Test
    void aGenieGoingOfflineShowsUpAtOnceInTheList() {
        ApiClient anon = new ApiClient(port);
        ApiClient genie = new ApiClient(port).login("genie@example.com");
        String genieId = genie.get("/api/v1/genie/profile").body().get("id").asString();
        try {
            genie.post("/api/v1/genie/status", "{\"online\":true}");
            assertThat(online(anon, genieId)).isTrue();
            assertThat(online(anon, genieId)).isTrue();                      // cached
            genie.post("/api/v1/genie/status", "{\"online\":false}");
            assertThat(online(anon, genieId)).isFalse();
            assertThat(anon.get("/api/v1/genies/" + genieId).body().get("genie").get("online").asBoolean()).isFalse();
        } finally {
            genie.post("/api/v1/genie/status", "{\"online\":true}");
        }
    }

    private static boolean online(ApiClient anon, String genieId) {
        for (JsonNode card : anon.get("/api/v1/genies?city=ahmedabad&limit=50").body()) {
            if (card.get("id").asString().equals(genieId)) {
                return card.get("online").asBoolean();
            }
        }
        throw new AssertionError("Genie " + genieId + " is not listed");
    }
}
