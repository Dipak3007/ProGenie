package com.progenie.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The API contract the web app is generated from (web/openapi.json) must match what the API actually serves.
 * When an endpoint or DTO changes, this fails until the snapshot and the generated client are refreshed:
 * start the API, then in web/ run {@code npm run api:update} and commit web/openapi.json together with
 * web/src/app/core/api/generated. (web-ci checks the generated client against the snapshot.)
 */
class OpenApiContractIT extends IntegrationTest {

    private static final Path SNAPSHOT = Path.of("../web/openapi.json");

    @Value("${local.server.port}")
    int port;

    @Test
    void theCommittedSnapshotMatchesTheLiveApi() throws Exception {
        assertThat(SNAPSHOT).as("web/openapi.json (run `npm run api:update` in web/ with the API running)").exists();
        ApiClient.Response res = new ApiClient(port).get("/v3/api-docs");
        assertThat(res.status()).isEqualTo(200);
        ObjectNode live = (ObjectNode) res.body();
        live.remove("servers"); // the port changes; not part of the contract
        JsonNode committed = ApiClient.JSON.readTree(Files.readString(SNAPSHOT));

        List<String> differences = new ArrayList<>();
        diff("paths", live.get("paths"), committed.get("paths"), differences);
        diff("schemas", live.path("components").get("schemas"), committed.path("components").get("schemas"), differences);
        if (differences.isEmpty() && !same(live, committed)) {
            differences.add("info, security or other top-level settings");
        }
        assertThat(differences)
            .as("The API changed but web/openapi.json did not. Start the API, run `npm run api:update` in web/, "
                + "and commit web/openapi.json plus web/src/app/core/api/generated. Different")
            .isEmpty();
    }

    /** JSON equality where 90 and 90.0 are the same number (the snapshot is written by JavaScript). */
    private static boolean same(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (String name : a.propertyNames()) {
                if (!b.has(name) || !same(a.get(name), b.get(name))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!same(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.equals(b);
    }

    private static void diff(String section, JsonNode live, JsonNode committed, List<String> out) {
        TreeSet<String> names = new TreeSet<>();
        if (live != null) {
            live.propertyNames().forEach(names::add);
        }
        if (committed != null) {
            committed.propertyNames().forEach(names::add);
        }
        for (String name : names) {
            JsonNode a = live == null ? null : live.get(name);
            JsonNode b = committed == null ? null : committed.get(name);
            if (a == null) {
                out.add(section + " " + name + " (removed from the API)");
            } else if (b == null) {
                out.add(section + " " + name + " (new in the API)");
            } else if (!same(a, b)) {
                out.add(section + " " + name + " (changed)");
            }
        }
    }
}
