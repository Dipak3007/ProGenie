package com.progenie.catalog.geo;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenStreetMap's Nominatim (https://nominatim.org). Free, no key, and its usage policy asks for: at most one request
 * per second, an identifying User-Agent, a contact e-mail, caching, and the "© OpenStreetMap" credit (on our maps).
 * This adapter sends one request at a time for the whole API, spaced by {@code minInterval}; the caller caches.
 *
 * <p>OSM's PIN codes in India are good in cities and patchy elsewhere, so answers are checked (six digits, India
 * only) and the caller falls back to our own PIN code list when there is none. When traffic grows, point
 * {@code nominatimUrl} at a self-hosted or paid Nominatim, or add another {@link Geocoder}.
 */
@Component
@ConditionalOnProperty(name = "progenie.geocoding.provider", havingValue = "nominatim", matchIfMissing = true)
class NominatimGeocoder implements Geocoder {

    private static final Logger log = LoggerFactory.getLogger(NominatimGeocoder.class);

    /** "380009", "380 009", "380009;380015" (OSM data is hand-typed): the first six-digit PIN code. */
    private static final Pattern PIN = Pattern.compile("(?<!\\d)([1-9]\\d{2})\\s?(\\d{3})(?!\\d)");
    /** Most specific locality first. */
    private static final String[] AREA_KEYS = {"suburb", "neighbourhood", "quarter", "city_district", "village",
        "town", "city"};

    private final GeocodingProperties props;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String base;
    private final ReentrantLock turn = new ReentrantLock(true);
    private long nextSlot;

    NominatimGeocoder(GeocodingProperties props) {
        this.props = props;
        this.base = props.nominatimUrl().replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
        log.info("Geocoding: Nominatim at {}{}", base, props.email().isBlank() ? " (set PROGENIE_GEOCODING_EMAIL on servers)" : "");
    }

    @Override
    public String name() {
        return "nominatim";
    }

    @Override
    public Optional<Place> pincode(String pincode) {
        JsonNode results = get("/search?postalcode=" + pincode + "&countrycodes=in&format=jsonv2&addressdetails=1&limit=1");
        if (!results.isArray() || results.isEmpty()) {
            return Optional.empty();
        }
        JsonNode hit = results.get(0);
        String found = pincodeOf(hit.path("address").path("postcode").asString(""));
        if (found != null && !found.equals(pincode)) {
            return Optional.empty();                    // a near match is not the PIN code the user typed
        }
        return place(hit, pincode);
    }

    @Override
    public Optional<Place> reverse(double lat, double lng) {
        JsonNode hit = get(String.format(Locale.ROOT, "/reverse?lat=%.6f&lon=%.6f&format=jsonv2&addressdetails=1&zoom=18", lat, lng));
        if (hit.has("error") || !"in".equalsIgnoreCase(hit.path("address").path("country_code").asString(""))) {
            return Optional.empty();
        }
        return place(hit, pincodeOf(hit.path("address").path("postcode").asString("")))
            .map(p -> new Place(lat, lng, p.pincode(), p.area()));    // keep the user's point, not OSM's object
    }

    // ------------------------------------------------------------------ helpers

    static @Nullable String pincodeOf(String raw) {
        Matcher m = PIN.matcher(raw == null ? "" : raw);
        return m.find() ? m.group(1) + m.group(2) : null;
    }

    private Optional<Place> place(JsonNode hit, @Nullable String pincode) {
        try {
            double lat = Double.parseDouble(hit.path("lat").asString(""));
            double lng = Double.parseDouble(hit.path("lon").asString(""));
            return Optional.of(new Place(lat, lng, pincode, area(hit.path("address"))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static @Nullable String area(JsonNode address) {
        for (String key : AREA_KEYS) {
            String v = address.path(key).asString("").trim();
            if (!v.isEmpty()) {
                return v.length() > 120 ? v.substring(0, 120) : v;
            }
        }
        return null;
    }

    private JsonNode get(String pathAndQuery) {
        String url = base + pathAndQuery
            + (props.email().isBlank() ? "" : "&email=" + URLEncoder.encode(props.email(), StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(props.timeout())
            .header("User-Agent", props.userAgent())
            .header("Accept", "application/json")
            .header("Accept-Language", "en")
            .GET()
            .build();
        waitForTurn();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new Unavailable("Nominatim answered " + res.statusCode(), true);
            }
            return json.readTree(res.body());
        } catch (IOException e) {
            throw new Unavailable("Nominatim unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted", e);
        } catch (RuntimeException e) {
            if (e instanceof Unavailable u) {
                throw u;
            }
            throw new Unavailable("Nominatim answer unreadable: " + e.getMessage(), e);
        }
    }

    /** One request at a time, at least {@code minInterval} apart; gives up rather than queue for long. */
    private void waitForTurn() {
        try {
            if (!turn.tryLock(props.maxWait().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new Unavailable("Nominatim busy (one request per " + props.minInterval().toMillis() + " ms)", false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted", e);
        }
        try {
            long wait = nextSlot - System.nanoTime();
            if (wait > 0) {
                if (wait > props.maxWait().toNanos()) {
                    throw new Unavailable("Nominatim busy", false);
                }
                Thread.sleep(Duration.ofNanos(wait));
            }
            nextSlot = System.nanoTime() + props.minInterval().toNanos();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted", e);
        } finally {
            turn.unlock();
        }
    }
}
