package com.progenie.pricing.distance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OsrmRouterTest {

    private HttpServer server;
    private volatile int status = 200;
    private volatile String body = "";
    private volatile String lastPath = "";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            lastPath = ex.getRequestURI().toString();
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

    private OsrmRouter router() {
        return new OsrmRouter(new DistanceProperties("osrm", "http://127.0.0.1:" + server.getAddress().getPort() + "/",
            new BigDecimal("1.3"), Duration.ofSeconds(2), 100, Duration.ofDays(1), Duration.ofMinutes(1)));
    }

    @Test
    void readsTheRouteAndSendsLongitudeFirst() {
        body = "{\"code\":\"Ok\",\"routes\":[{\"distance\":7345.2,\"duration\":912.4,\"legs\":[]}],\"waypoints\":[]}";
        RoadRouter.Route r = router().route(23.04, 72.505, 23.035, 72.561).orElseThrow();
        assertThat(r.meters()).isEqualTo(7345.2);
        assertThat(r.seconds()).isEqualTo(912.4);
        assertThat(lastPath).startsWith("/route/v1/driving/72.505000,23.040000;72.561000,23.035000?overview=false");
    }

    @Test
    void noRouteIsAnEmptyAnswer() {
        status = 400;
        body = "{\"code\":\"NoRoute\",\"message\":\"Impossible route between points\"}";
        assertThat(router().route(23.04, 72.505, 20.0, 70.0)).isEmpty();
    }

    @Test
    void errorsAreUnavailable() {
        status = 400;
        body = "{\"code\":\"InvalidQuery\"}";
        assertThatThrownBy(() -> router().route(1, 2, 3, 4)).isInstanceOf(RoadRouter.Unavailable.class);
        status = 502;
        body = "<html>Bad gateway</html>";
        assertThatThrownBy(() -> router().route(1, 2, 3, 4)).isInstanceOf(RoadRouter.Unavailable.class);
        OsrmRouter nobodyHome = new OsrmRouter(new DistanceProperties("osrm", "http://127.0.0.1:1", new BigDecimal("1.3"),
            Duration.ofSeconds(2), 100, Duration.ofDays(1), Duration.ofMinutes(1)));
        assertThatThrownBy(() -> nobodyHome.route(1, 2, 3, 4)).isInstanceOf(RoadRouter.Unavailable.class);
    }
}
