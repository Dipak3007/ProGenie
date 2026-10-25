package com.progenie.pricing.distance;

import java.util.Optional;

/**
 * Port to a road routing engine: how far by road from A to B. Chosen by {@code progenie.distance.provider}:
 * {@code osrm} (a self-hosted OSRM server) or {@code straight} (no router; the straight-line estimate is used).
 *
 * <p>Adapters answer {@link Optional#empty()} when there is no route, and throw {@link Unavailable} when the router
 * could not be asked (error, timeout). {@link DistanceService} falls back to the estimate either way.
 */
public interface RoadRouter {

    /** A driving route: length in metres and duration in seconds. */
    record Route(double meters, double seconds) {
    }

    String name();

    default boolean enabled() {
        return true;
    }

    Optional<Route> route(double fromLat, double fromLng, double toLat, double toLng);

    /** The router could not be asked; the answer is unknown. */
    final class Unavailable extends RuntimeException {

        public Unavailable(String message) {
            super(message);
        }

        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
