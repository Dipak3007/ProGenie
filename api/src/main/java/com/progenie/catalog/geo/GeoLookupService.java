package com.progenie.catalog.geo;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.progenie.catalog.CatalogDtos.GeoSource;
import com.progenie.catalog.CatalogDtos.PincodeAtDto;
import com.progenie.catalog.CatalogDtos.PincodeCheckDto;
import com.progenie.catalog.CatalogDtos.PincodeLocationDto;
import com.progenie.catalog.ServiceabilityService;
import com.progenie.catalog.geo.Geocoder.Place;
import com.progenie.shared.cache.TtlCache;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.ratelimit.RateLimiter;
import com.progenie.shared.security.CurrentUser;
import com.progenie.shared.util.QueryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Keeps an address's PIN code and map pin in step (address form):
 * <ul>
 *   <li>PIN code → point: our own centre for a PIN code we serve (no outside call), else the map provider.</li>
 *   <li>point → PIN code: the map provider, else the nearest PIN code we serve within
 *       {@code fallbackRadiusKm}.</li>
 * </ul>
 * Provider answers are cached; each user can cause at most {@code perUserPerHour} provider lookups, after which only
 * our own data is used. Nothing here holds a database transaction across the provider call.
 */
@Service
public class GeoLookupService {

    private static final Logger log = LoggerFactory.getLogger(GeoLookupService.class);
    /** "No answer" is kept for a shorter time than an answer: OSM data improves. */
    private static final Duration MISS_TTL = Duration.ofHours(1);

    private final Geocoder geocoder;
    private final GeocodingProperties props;
    private final ServiceabilityService serviceability;
    private final RateLimiter rateLimiter;
    private final JdbcClient jdbc;
    private final TtlCache<Optional<Place>> pincodes;
    private final TtlCache<Optional<Place>> points;
    private volatile long pausedUntilNanos = System.nanoTime();

    public GeoLookupService(Geocoder geocoder, GeocodingProperties props, ServiceabilityService serviceability,
                            RateLimiter rateLimiter, JdbcClient jdbc) {
        this.geocoder = geocoder;
        this.props = props;
        this.serviceability = serviceability;
        this.rateLimiter = rateLimiter;
        this.jdbc = jdbc;
        this.pincodes = new TtlCache<>(props.cacheSize());
        this.points = new TtlCache<>(props.cacheSize());
    }

    /** Where is this PIN code? Its rough centre, for moving the map pin there. */
    public PincodeLocationDto locate(String pincode) {
        String pin = pincode == null ? "" : pincode.trim();
        if (!pin.matches(ServiceabilityService.PINCODE)) {
            throw ApiException.badRequest("INVALID_PINCODE", "Enter a valid 6-digit PIN code");
        }
        PincodeCheckDto served = serviceability.checkPincode(pin);
        if (served.serviceable() && served.lat() != null && served.lng() != null) {
            return new PincodeLocationDto(pin, true, served.lat(), served.lng(), served.area(), GeoSource.SERVED);
        }
        Optional<Place> found = ask(pincodes, pin, () -> geocoder.pincode(pin))
            .filter(p -> serviceability.inIndia(p.lat(), p.lng()));
        if (found.isPresent()) {
            Place p = found.get();
            String area = served.serviceable() && served.area() != null ? served.area() : p.area();
            return new PincodeLocationDto(pin, true, p.lat(), p.lng(), area, GeoSource.GEOCODER);
        }
        return new PincodeLocationDto(pin, false, null, null, null, null);
    }

    /** Which PIN code is this point in? Null PIN code when nobody knows (the user types it). */
    public PincodeAtDto pincodeAt(double lat, double lng) {
        if (!serviceability.inIndia(lat, lng)) {
            return new PincodeAtDto(lat, lng, false, null, null, null);
        }
        String key = String.format(Locale.ROOT, "%.4f,%.4f", lat, lng);       // ~11 m: the same spot
        Optional<Place> here = ask(points, key, () -> geocoder.reverse(lat, lng));
        if (here.isPresent() && here.get().pincode() != null) {
            String pin = here.get().pincode();
            PincodeCheckDto served = serviceability.checkPincode(pin);
            String area = served.serviceable() && served.area() != null ? served.area() : here.get().area();
            return new PincodeAtDto(lat, lng, true, pin, area, GeoSource.GEOCODER);
        }
        return nearestServed(lat, lng)
            .orElseGet(() -> new PincodeAtDto(lat, lng, true, null, null, null));
    }

    // ------------------------------------------------------------------ helpers

    private Optional<PincodeAtDto> nearestServed(double lat, double lng) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_SERVICEABILITY_NEAREST_PINCODE))
            .param("lat", lat).param("lng", lng).param("meters", props.fallbackRadiusKm() * 1000)
            .query((rs, n) -> new PincodeAtDto(lat, lng, true, rs.getString("pincode"), rs.getString("area"),
                GeoSource.NEAREST))
            .optional();
    }

    /** Cache first; then the provider, if it is on and this user still has lookups left. */
    private Optional<Place> ask(TtlCache<Optional<Place>> cache, String key, Supplier<Optional<Place>> call) {
        if (!geocoder.enabled()) {
            return Optional.empty();
        }
        Optional<Place> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        if (System.nanoTime() - pausedUntilNanos < 0 || !withinUserLimit()) {
            return Optional.empty();
        }
        try {
            Optional<Place> answer = call.get();
            cache.put(key, answer, answer.isPresent() ? props.cacheTtl() : MISS_TTL);
            return answer;
        } catch (Geocoder.Unavailable e) {
            if (e.down()) {
                pausedUntilNanos = System.nanoTime() + props.pauseAfterFailure().toNanos();  // each try would cost a timeout
                log.warn("Geocoding via {} failed ({}); using own data only for {}", geocoder.name(), e.getMessage(), props.pauseAfterFailure());
            } else {
                log.info("Geocoding via {} skipped: {}", geocoder.name(), e.getMessage());
            }
            return Optional.empty();
        }
    }

    private boolean withinUserLimit() {
        UUID user = CurrentUser.idOrNull();
        if (user == null) {
            return true;
        }
        boolean ok = rateLimiter.tryAcquire("geo:" + user, props.perUserPerHour(), Duration.ofHours(1));
        if (!ok) {
            log.debug("Geocoding limit reached for user {}; using own data only", user);
        }
        return ok;
    }
}
