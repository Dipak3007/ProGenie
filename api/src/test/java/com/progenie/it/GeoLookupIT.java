package com.progenie.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/**
 * PIN code ↔ map point (address form) through HTTP, with a stand-in for Nominatim so the test never leaves the
 * machine: our own centres first, then the provider, then the nearest served PIN code; answers are cached.
 */
class GeoLookupIT extends IntegrationTest {

    private static final HttpServer NOMINATIM;
    private static final Map<String, AtomicInteger> HITS = new ConcurrentHashMap<>();

    static {
        try {
            NOMINATIM = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        NOMINATIM.createContext("/search", GeoLookupIT::search);
        NOMINATIM.createContext("/reverse", GeoLookupIT::reverse);
        NOMINATIM.start();
    }

    @DynamicPropertySource
    static void geocoder(DynamicPropertyRegistry registry) {
        registry.add("progenie.geocoding.provider", () -> "nominatim");
        registry.add("progenie.geocoding.nominatim-url", () -> "http://127.0.0.1:" + NOMINATIM.getAddress().getPort());
        registry.add("progenie.geocoding.min-interval", () -> "20ms");
        registry.add("progenie.geocoding.email", () -> "it@example.com");
        registry.add("progenie.geocoding.per-user-per-hour", () -> "100000");   // re-runs on one database
        registry.add("progenie.geocoding.pause-after-failure", () -> "0s");      // the 502 case must not pause the others
    }

    @Value("${local.server.port}")
    int port;

    @Test
    void aPincodeWeServeUsesOurOwnCentreWithoutAskingTheProvider() {
        JsonNode r = customer().get("/api/v1/geo/pincodes/380009").body();
        assertThat(r.get("found").asBoolean()).isTrue();
        assertThat(r.get("source").asString()).isEqualTo("SERVED");
        assertThat(r.get("lat").asDouble()).isBetween(23.0, 23.1);
        assertThat(hits("search:380009")).isZero();
    }

    @Test
    void otherPincodesComeFromTheProviderAndAreCached() {
        ApiClient c = customer();
        JsonNode r = c.get("/api/v1/geo/pincodes/400001").body();
        assertThat(r.get("found").asBoolean()).isTrue();
        assertThat(r.get("source").asString()).isEqualTo("GEOCODER");
        assertThat(r.get("lat").asDouble()).isBetween(18.9, 19.0);
        c.get("/api/v1/geo/pincodes/400001");
        assertThat(hits("search:400001")).as("second answer from the cache").isEqualTo(1);

        assertThat(c.get("/api/v1/geo/pincodes/110001").body().get("found").asBoolean())
            .as("the provider's nearest match is another PIN code").isFalse();
        assertThat(c.get("/api/v1/geo/pincodes/012345").status()).isEqualTo(400);
    }

    @Test
    void aPointGetsTheProvidersPincodeElseTheNearestWeServe() {
        ApiClient c = customer();
        JsonNode bandra = c.get("/api/v1/geo/pincode-at?lat=19.0596&lng=72.8295").body();
        assertThat(bandra.get("pincode").asString()).isEqualTo("400050");          // "400 050" in OSM
        assertThat(bandra.get("source").asString()).isEqualTo("GEOCODER");
        assertThat(bandra.get("area").asString()).isEqualTo("Bandra West");

        JsonNode navrangpura = c.get("/api/v1/geo/pincode-at?lat=23.0352&lng=72.5612").body();
        assertThat(navrangpura.get("pincode").asString()).isEqualTo("380009");
        assertThat(navrangpura.get("area").asString()).as("our area name for a served PIN code").isEqualTo("Navrangpura");

        JsonNode noPostcode = c.get("/api/v1/geo/pincode-at?lat=23.0290&lng=72.5190").body();
        assertThat(noPostcode.get("source").asString()).isEqualTo("NEAREST");
        assertThat(noPostcode.get("pincode").asString()).isEqualTo("380015");
        c.get("/api/v1/geo/pincode-at?lat=23.02901&lng=72.51902");
        assertThat(hits("reverse:23.0290,72.5190")).as("the same spot (~11 m) is cached").isEqualTo(1);

        JsonNode down = c.get("/api/v1/geo/pincode-at?lat=23.0570&lng=72.5545").body();
        assertThat(down.get("source").asString()).as("provider error: fall back").isEqualTo("NEAREST");
        assertThat(down.get("pincode").asString()).isEqualTo("380013");

        JsonNode nowhere = c.get("/api/v1/geo/pincode-at?lat=26.9000&lng=70.9000").body();   // Thar desert
        assertThat(nowhere.get("inIndia").asBoolean()).isTrue();
        assertThat(nowhere.get("pincode").isNull()).isTrue();

        JsonNode karachi = c.get("/api/v1/geo/pincode-at?lat=24.86&lng=67.01").body();
        assertThat(karachi.get("inIndia").asBoolean()).isFalse();
        assertThat(hits("reverse:24.8600,67.0100")).as("outside India: not even asked").isZero();
    }

    @Test
    void lookupsNeedALogin() {
        assertThat(new ApiClient(port).get("/api/v1/geo/pincode-at?lat=23.03&lng=72.56").status()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ stand-in Nominatim

    private ApiClient customer() {
        return new ApiClient(port).login("customer@example.com");
    }

    private static int hits(String key) {
        AtomicInteger n = HITS.get(key);
        return n == null ? 0 : n.get();
    }

    private static void search(HttpExchange ex) throws IOException {
        String pin = param(ex, "postalcode");
        HITS.computeIfAbsent("search:" + pin, k -> new AtomicInteger()).incrementAndGet();
        String body = switch (pin) {
            case "400001" -> "[{\"lat\":\"18.9375\",\"lon\":\"72.8355\",\"address\":{\"postcode\":\"400001\",\"suburb\":\"Fort\",\"country_code\":\"in\"}}]";
            case "110001" -> "[{\"lat\":\"28.63\",\"lon\":\"77.21\",\"address\":{\"postcode\":\"110002\",\"country_code\":\"in\"}}]";
            default -> "[]";
        };
        respond(ex, 200, body);
    }

    private static void reverse(HttpExchange ex) throws IOException {
        if (!"it@example.com".equals(param(ex, "email")) || !ex.getRequestHeaders().getFirst("User-Agent").startsWith("ProGenie")) {
            respond(ex, 403, "{\"error\":\"identify yourself\"}");
            return;
        }
        String key = String.format(java.util.Locale.ROOT, "%.4f,%.4f",
            Double.parseDouble(param(ex, "lat")), Double.parseDouble(param(ex, "lon")));
        HITS.computeIfAbsent("reverse:" + key, k -> new AtomicInteger()).incrementAndGet();
        switch (key) {
            case "19.0596,72.8295" -> respond(ex, 200, "{\"lat\":\"19.0597\",\"lon\":\"72.8296\",\"address\":{\"suburb\":\"Bandra West\",\"city\":\"Mumbai\",\"postcode\":\"400 050\",\"country_code\":\"in\"}}");
            case "23.0352,72.5612" -> respond(ex, 200, "{\"lat\":\"23.0352\",\"lon\":\"72.5612\",\"address\":{\"suburb\":\"Navrangpura\",\"city\":\"Ahmedabad\",\"postcode\":\"380009\",\"country_code\":\"in\"}}");
            case "23.0570,72.5545" -> respond(ex, 502, "Bad gateway");
            case "23.0290,72.5190" -> respond(ex, 200, "{\"lat\":\"23.029\",\"lon\":\"72.519\",\"address\":{\"road\":\"SG Highway\",\"city\":\"Ahmedabad\",\"country_code\":\"in\"}}");
            default -> respond(ex, 200, "{\"error\":\"Unable to geocode\"}");
        }
    }

    private static String param(HttpExchange ex, String name) {
        for (String pair : ex.getRequestURI().getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }
}
