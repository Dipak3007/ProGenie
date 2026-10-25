package com.progenie.catalog.geo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NominatimGeocoderTest {

    private HttpServer server;
    private final List<Long> calls = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "[]";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            calls.add(System.nanoTime());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private NominatimGeocoder geocoder(Duration minInterval, Duration maxWait) {
        return new NominatimGeocoder(new GeocodingProperties("nominatim", "http://127.0.0.1:" + server.getAddress().getPort() + "/",
            "", "ProGenie-test", minInterval, maxWait, Duration.ofSeconds(2), 100, Duration.ofDays(1), 60, 5, Duration.ofMinutes(1)));
    }

    @Test
    void readsPincodesTheWayPeopleTypeThemInOsm() {
        assertThat(NominatimGeocoder.pincodeOf("380009")).isEqualTo("380009");
        assertThat(NominatimGeocoder.pincodeOf("380 009")).isEqualTo("380009");
        assertThat(NominatimGeocoder.pincodeOf("380009;380015")).isEqualTo("380009");
        assertThat(NominatimGeocoder.pincodeOf("Ahmedabad - 380015")).isEqualTo("380015");
        assertThat(NominatimGeocoder.pincodeOf("080009")).isNull();
        assertThat(NominatimGeocoder.pincodeOf("3800091")).isNull();
        assertThat(NominatimGeocoder.pincodeOf("")).isNull();
    }

    @Test
    void reverseKeepsTheUsersPointAndPicksTheMostSpecificArea() {
        body = "{\"lat\":\"23.1\",\"lon\":\"72.6\",\"address\":{\"neighbourhood\":\"Vijay Char Rasta\",\"suburb\":\"Navrangpura\","
            + "\"city\":\"Ahmedabad\",\"postcode\":\"380 009\",\"country_code\":\"in\"}}";
        Geocoder.Place p = geocoder(Duration.ZERO, Duration.ofSeconds(1)).reverse(23.035, 72.561).orElseThrow();
        assertThat(p.lat()).isEqualTo(23.035);
        assertThat(p.lng()).isEqualTo(72.561);
        assertThat(p.pincode()).isEqualTo("380009");
        assertThat(p.area()).isEqualTo("Navrangpura");
    }

    @Test
    void noAnswerOutsideIndiaOrWhenNominatimHasNothing() {
        NominatimGeocoder g = geocoder(Duration.ZERO, Duration.ofSeconds(1));
        body = "{\"error\":\"Unable to geocode\"}";
        assertThat(g.reverse(26.9, 70.9)).isEmpty();
        body = "{\"lat\":\"24.86\",\"lon\":\"67.01\",\"address\":{\"postcode\":\"74000\",\"country_code\":\"pk\"}}";
        assertThat(g.reverse(24.86, 67.01)).isEmpty();
        body = "[]";
        assertThat(g.pincode("380009")).isEmpty();
    }

    @Test
    void anErrorIsUnavailableNotNoAnswer() {
        status = 429;
        assertThatThrownBy(() -> geocoder(Duration.ZERO, Duration.ofSeconds(1)).pincode("380009"))
            .isInstanceOf(Geocoder.Unavailable.class);
    }

    @Test
    void sendsAtMostOneRequestPerInterval() {
        NominatimGeocoder g = geocoder(Duration.ofMillis(300), Duration.ofSeconds(2));
        long start = System.nanoTime();
        g.pincode("380009");
        g.pincode("380015");
        g.pincode("380054");
        assertThat(calls).hasSize(3);
        // the 2nd and 3rd requests each waited for their turn (measured here: network timing on the server side jitters)
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(600));
    }

    @Test
    void givesUpRatherThanQueueForLong() {
        NominatimGeocoder g = geocoder(Duration.ofSeconds(5), Duration.ofMillis(100));
        g.pincode("380009");
        assertThatThrownBy(() -> g.pincode("380015")).isInstanceOf(Geocoder.Unavailable.class);
        assertThat(calls).hasSize(1);
    }
}
