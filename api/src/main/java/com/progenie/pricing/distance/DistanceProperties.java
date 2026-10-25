package com.progenie.pricing.distance;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code progenie.distance.*}: the distance the travel fee is charged on (Genie's base → customer's address).
 *
 * @param provider          straight (straight line × {@code roadFactor}, the default) | osrm (road distance from an
 *                          OSRM server, with the straight-line estimate as fallback)
 * @param osrmUrl           OSRM base URL, e.g. http://localhost:5000 (infra/osrm, Compose profile "osrm")
 * @param roadFactor        straight line × this ≈ road distance, when there is no road answer
 * @param timeout           per OSRM request; a booking never waits longer than this for a distance
 * @param cacheSize         routes kept in memory (a Genie's base and an address rarely move)
 * @param cacheTtl          how long a route is kept; also keeps the estimate and the booking at the same price
 * @param pauseAfterFailure after an OSRM error or timeout it is not asked again for this long (estimate only)
 */
@ConfigurationProperties(prefix = "progenie.distance")
public record DistanceProperties(
    @DefaultValue("straight") String provider,
    @DefaultValue("http://localhost:5000") String osrmUrl,
    @DefaultValue("1.3") BigDecimal roadFactor,
    @DefaultValue("2s") Duration timeout,
    @DefaultValue("20000") int cacheSize,
    @DefaultValue("7d") Duration cacheTtl,
    @DefaultValue("1m") Duration pauseAfterFailure) {
}
