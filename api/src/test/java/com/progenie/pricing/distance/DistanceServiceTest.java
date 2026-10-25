package com.progenie.pricing.distance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.progenie.pricing.PriceCalculator.DistanceMethod;
import com.progenie.pricing.distance.DistanceService.TravelDistance;
import org.junit.jupiter.api.Test;

class DistanceServiceTest {

    private static final BigDecimal STRAIGHT = new BigDecimal("5.00");

    private final AtomicInteger calls = new AtomicInteger();

    private DistanceService service(Supplier<Optional<RoadRouter.Route>> answer, Duration pause) {
        RoadRouter router = new RoadRouter() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public Optional<Route> route(double fromLat, double fromLng, double toLat, double toLng) {
                calls.incrementAndGet();
                return answer.get();
            }
        };
        return new DistanceService(router, new DistanceProperties("osrm", "http://x", new BigDecimal("1.3"),
            Duration.ofSeconds(1), 100, Duration.ofDays(1), pause));
    }

    private static TravelDistance trip(DistanceService s) {
        return s.travel(23.04, 72.50, 23.03, 72.56, STRAIGHT);
    }

    @Test
    void usesTheRoadDistanceAndCachesIt() {
        DistanceService s = service(() -> Optional.of(new RoadRouter.Route(7_345, 900)), Duration.ofMinutes(1));
        assertThat(trip(s)).isEqualTo(new TravelDistance(new BigDecimal("7.35"), DistanceMethod.ROAD));
        assertThat(trip(s).km()).isEqualByComparingTo("7.35");
        assertThat(calls).as("the estimate and the booking ask once").hasValue(1);
    }

    @Test
    void aRoadIsNeverShorterThanTheStraightLine() {
        DistanceService s = service(() -> Optional.of(new RoadRouter.Route(4_100, 500)), Duration.ofMinutes(1));
        assertThat(trip(s).km()).isEqualByComparingTo("5.00");
    }

    @Test
    void noRouteMeansTheEstimate() {
        DistanceService s = service(Optional::empty, Duration.ofMinutes(1));
        assertThat(trip(s)).isEqualTo(new TravelDistance(new BigDecimal("6.50"), DistanceMethod.ESTIMATE));
    }

    @Test
    void aFailingRouterRestsSoBookingsDontWaitOnIt() {
        DistanceService s = service(() -> {
            throw new RoadRouter.Unavailable("connection refused");
        }, Duration.ofMinutes(1));
        assertThat(trip(s).method()).isEqualTo(DistanceMethod.ESTIMATE);
        assertThat(s.travel(22.0, 72.0, 22.1, 72.1, STRAIGHT).method()).isEqualTo(DistanceMethod.ESTIMATE);
        assertThat(calls).as("not asked again during the pause").hasValue(1);
    }

    @Test
    void withoutARouterItIsTheOldRule() {
        DistanceService s = new DistanceService(new StraightLineOnly(), new DistanceProperties("straight", "", new BigDecimal("1.3"),
            Duration.ofSeconds(1), 100, Duration.ofDays(1), Duration.ofMinutes(1)));
        assertThat(trip(s)).isEqualTo(new TravelDistance(new BigDecimal("6.50"), DistanceMethod.ESTIMATE));
    }
}
