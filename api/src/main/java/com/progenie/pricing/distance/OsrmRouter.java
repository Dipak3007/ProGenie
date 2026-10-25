package com.progenie.pricing.distance;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OSRM (Open Source Routing Machine, http://project-osrm.org): driving distance on OpenStreetMap roads.
 * Run your own server (infra/osrm: Gujarat data, about 1–2 GB of memory); the public demo server is for trying
 * things only. One HTTP call per route: {@code /route/v1/driving/{lng},{lat};{lng},{lat}?overview=false}.
 */
@Component
@ConditionalOnProperty(name = "progenie.distance.provider", havingValue = "osrm")
class OsrmRouter implements RoadRouter {

    private static final Logger log = LoggerFactory.getLogger(OsrmRouter.class);

    private final DistanceProperties props;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();
    private final String base;

    OsrmRouter(DistanceProperties props) {
        this.props = props;
        this.base = props.osrmUrl().replaceAll("/+$", "");
        this.http = HttpClient.newBuilder().connectTimeout(props.timeout()).build();
        log.info("Travel distance: road distance from OSRM at {} (fallback: straight line × {})", base, props.roadFactor());
    }

    @Override
    public String name() {
        return "osrm";
    }

    @Override
    public Optional<Route> route(double fromLat, double fromLng, double toLat, double toLng) {
        String url = String.format(Locale.ROOT, "%s/route/v1/driving/%.6f,%.6f;%.6f,%.6f?overview=false&alternatives=false&steps=false",
            base, fromLng, fromLat, toLng, toLat);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(props.timeout())
            .header("Accept", "application/json").GET().build();
        JsonNode body;
        int status;
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            status = res.statusCode();
            body = json.readTree(res.body());
        } catch (IOException e) {
            throw new Unavailable("OSRM unreachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted", e);
        } catch (RuntimeException e) {
            throw new Unavailable("OSRM answer unreadable: " + e.getMessage(), e);
        }
        String code = body.path("code").asString("");
        if (status == 200 && "Ok".equals(code)) {
            JsonNode r = body.path("routes").path(0);
            if (r.has("distance")) {
                return Optional.of(new Route(r.path("distance").asDouble(), r.path("duration").asDouble()));
            }
            return Optional.empty();
        }
        // NoRoute / NoSegment: the points aren't connected by road in the loaded data (or are off the map)
        if ("NoRoute".equals(code) || "NoSegment".equals(code)) {
            return Optional.empty();
        }
        throw new Unavailable("OSRM answered " + status + (code.isEmpty() ? "" : " " + code));
    }
}
