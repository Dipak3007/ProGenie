package com.progenie.catalog.geo;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * Port to a map data provider: "where is PIN code X?" and "which PIN code is this point in?".
 * The adapter is chosen by {@code progenie.geocoding.provider}: {@code nominatim} (OpenStreetMap, the default) or
 * {@code none}. A paid provider (Google, Mappls) is one more adapter.
 *
 * <p>Adapters answer {@link Optional#empty()} when the provider has no answer, and throw {@link Unavailable} when it
 * could not be asked (timeout, error, rate limit). Callers fall back to ProGenie's own PIN code list either way, but
 * only the first is cached.
 */
public interface Geocoder {

    /** A point, with the PIN code and locality the provider knows for it (either can be missing). */
    record Place(double lat, double lng, @Nullable String pincode, @Nullable String area) {
    }

    /** Short name for logs, e.g. "nominatim". */
    String name();

    /** False for the "none" adapter: callers then skip it entirely. */
    default boolean enabled() {
        return true;
    }

    /** The rough centre of an Indian PIN code. */
    Optional<Place> pincode(String pincode);

    /** What is at this point; {@link Place#pincode()} is null when the provider has no PIN code there. */
    Optional<Place> reverse(double lat, double lng);

    /**
     * The provider could not be asked right now; the answer is unknown (not "no result"). {@code down}: it failed
     * (error, timeout, refused), so callers pause it for a while; false when it was only busy.
     */
    final class Unavailable extends RuntimeException {

        private final boolean down;

        public Unavailable(String message, boolean down) {
            super(message);
            this.down = down;
        }

        public Unavailable(String message, Throwable cause) {
            super(message, cause);
            this.down = true;
        }

        public boolean down() {
            return down;
        }
    }
}
