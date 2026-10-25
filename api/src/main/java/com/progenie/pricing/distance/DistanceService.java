package com.progenie.pricing.distance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Optional;

import com.progenie.pricing.PriceCalculator.DistanceMethod;
import com.progenie.pricing.distance.RoadRouter.Route;
import com.progenie.shared.cache.TtlCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The distance the travel fee is charged on: road distance from the {@link RoadRouter} when there is one, else the
 * straight line × {@code roadFactor} (the rule before OSRM). Routes are cached, so the price estimate and the
 * booking made from it a minute later get the same distance; after a router error the router rests for
 * {@code pauseAfterFailure} so no booking waits on a dead server.
 */
@Service
public class DistanceService {

    private static final Logger log = LoggerFactory.getLogger(DistanceService.class);

    /** km, rounded to 2 decimals, and how it was measured. */
    public record TravelDistance(BigDecimal km, DistanceMethod method) {
    }

    private final RoadRouter router;
    private final DistanceProperties props;
    private final TtlCache<Optional<Route>> routes;
    private volatile long pausedUntilNanos = System.nanoTime();

    public DistanceService(RoadRouter router, DistanceProperties props) {
        this.router = router;
        this.props = props;
        this.routes = new TtlCache<>(props.cacheSize());
    }

    /**
     * From the Genie's base to the customer. {@code straightKm} is the straight-line distance PostGIS already
     * measured (it is the fallback, and a sanity floor: a road is never shorter than the straight line).
     */
    public TravelDistance travel(double fromLat, double fromLng, double toLat, double toLng, BigDecimal straightKm) {
        Optional<Route> road = road(fromLat, fromLng, toLat, toLng);
        if (road.isPresent()) {
            BigDecimal km = BigDecimal.valueOf(road.get().meters() / 1000.0).max(straightKm);
            return new TravelDistance(km.setScale(2, RoundingMode.HALF_UP), DistanceMethod.ROAD);
        }
        return new TravelDistance(straightKm.multiply(props.roadFactor()).setScale(2, RoundingMode.HALF_UP),
            DistanceMethod.ESTIMATE);
    }

    private Optional<Route> road(double fromLat, double fromLng, double toLat, double toLng) {
        if (!router.enabled() || System.nanoTime() - pausedUntilNanos < 0) {
            return Optional.empty();
        }
        String key = String.format(Locale.ROOT, "%.5f,%.5f>%.5f,%.5f", fromLat, fromLng, toLat, toLng);   // ~1 m
        Optional<Route> cached = routes.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Optional<Route> answer = router.route(fromLat, fromLng, toLat, toLng);
            routes.put(key, answer, props.cacheTtl());
            return answer;
        } catch (RoadRouter.Unavailable e) {
            pausedUntilNanos = System.nanoTime() + props.pauseAfterFailure().toNanos();
            log.warn("Road distance via {} failed ({}); using straight line × {} for {}", router.name(), e.getMessage(),
                props.roadFactor(), props.pauseAfterFailure());
            return Optional.empty();
        }
    }
}
