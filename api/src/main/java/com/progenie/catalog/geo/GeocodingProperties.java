package com.progenie.catalog.geo;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code progenie.geocoding.*}: finding a PIN code's location and the PIN code of a map pin (address form).
 *
 * @param provider          nominatim (OpenStreetMap's free geocoder) | none (only ProGenie's own PIN code list)
 * @param nominatimUrl      base URL; point it at your own Nominatim server or a hosted one when traffic grows
 * @param email             contact address sent with each request, as Nominatim's usage policy asks; set it on servers
 * @param userAgent         identifies the app to the provider (required by Nominatim's usage policy)
 * @param minInterval       at most one provider request per this interval for the whole API (Nominatim: 1 per second)
 * @param maxWait           a request that would wait longer than this for its turn is not sent (own data is used)
 * @param timeout           per provider request
 * @param cacheSize         answers kept in memory (PIN codes and points, about 200 bytes each)
 * @param cacheTtl          how long an answer is kept
 * @param perUserPerHour    provider lookups one user can cause per hour (cache hits are free); then own data only
 * @param fallbackRadiusKm  without a provider answer, the nearest PIN code we serve within this distance is used
 * @param pauseAfterFailure after a provider error or timeout it is not asked again for this long (own data only)
 */
@ConfigurationProperties(prefix = "progenie.geocoding")
public record GeocodingProperties(
    @DefaultValue("nominatim") String provider,
    @DefaultValue("https://nominatim.openstreetmap.org") String nominatimUrl,
    @DefaultValue("") String email,
    @DefaultValue("ProGenie/1.0 (home services booking app)") String userAgent,
    @DefaultValue("1s") Duration minInterval,
    @DefaultValue("3s") Duration maxWait,
    @DefaultValue("4s") Duration timeout,
    @DefaultValue("5000") int cacheSize,
    @DefaultValue("7d") Duration cacheTtl,
    @DefaultValue("60") int perUserPerHour,
    @DefaultValue("5") double fallbackRadiusKm,
    @DefaultValue("1m") Duration pauseAfterFailure) {
}
