package com.progenie.pricing.distance;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** {@code progenie.distance.provider=straight} (the default): no router; straight line × road factor. */
@Component
@ConditionalOnProperty(name = "progenie.distance.provider", havingValue = "straight", matchIfMissing = true)
class StraightLineOnly implements RoadRouter {

    @Override
    public String name() {
        return "straight";
    }

    @Override
    public boolean enabled() {
        return false;
    }

    @Override
    public Optional<Route> route(double fromLat, double fromLng, double toLat, double toLng) {
        return Optional.empty();
    }
}
