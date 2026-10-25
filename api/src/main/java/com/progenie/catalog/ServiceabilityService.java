package com.progenie.catalog;

import java.util.List;
import java.util.Optional;

import com.progenie.catalog.CatalogDtos.AreaDto;
import com.progenie.catalog.CatalogDtos.CityDto;
import com.progenie.catalog.CatalogDtos.LocationCheckDto;
import com.progenie.catalog.CatalogDtos.PincodeCheckDto;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where ProGenie works. Everything here is data, so opening a city needs no code change:
 * <ul>
 *   <li>cities: live ones have a centre and a service radius,</li>
 *   <li>serviceable_pincodes: an address's city is the city of its PIN code,</li>
 *   <li>geo_regions 'IN': India's boundary; pins and base locations outside it are refused.</li>
 * </ul>
 * Other modules (customer addresses, Genie profiles) use the public methods below.
 */
@Service
public class ServiceabilityService {

    /** Indian PIN codes are six digits and never start with 0. */
    public static final String PINCODE = "^[1-9][0-9]{5}$";

    /** Result of {@link #requireServedAddress}: the city and the PIN code's area name. */
    public record ServedAddress(long cityId, String cityName, String area) {
    }

    private final JdbcClient jdbc;

    public ServiceabilityService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ public endpoints

    @Transactional(readOnly = true)
    public PincodeCheckDto checkPincode(String pincode) {
        String pin = pincode == null ? "" : pincode.trim();
        if (!pin.matches(PINCODE)) {
            throw ApiException.badRequest("INVALID_PINCODE", "Enter a valid 6-digit PIN code");
        }
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_SERVICEABILITY_PINCODE))
            .param("pincode", pin)
            .query((rs, n) -> new PincodeCheckDto(rs.getString("pincode"), true, rs.getLong("city_id"),
                rs.getString("city_slug"), rs.getString("city_name"), rs.getString("city_state"),
                rs.getString("area"), (Double) rs.getObject("lat"), (Double) rs.getObject("lng")))
            .optional()
            .orElseGet(() -> PincodeCheckDto.notServed(pin));
    }

    @Transactional(readOnly = true)
    public LocationCheckDto checkLocation(double lat, double lng) {
        if (!inIndia(lat, lng)) {
            return new LocationCheckDto(lat, lng, false, false, null);
        }
        Optional<CityDto> city = liveCityAt(lat, lng);
        return new LocationCheckDto(lat, lng, true, city.isPresent(), city.orElse(null));
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CatalogCaches.CITY, key = "#slug")
    public CityDto city(String slug) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_ACTIVE_CITY_BY_SLUG))
            .param("slug", slug)
            .query(CityDto.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("CITY_NOT_FOUND", "We are not live in " + slug + " yet"));
    }

    @Transactional(readOnly = true)
    @Cacheable(cacheNames = CatalogCaches.AREAS, key = "#slug")
    public List<AreaDto> areas(String slug) {
        city(slug);
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_CITY_AREAS))
            .param("slug", slug)
            .query(AreaDto.class)
            .list();
    }

    // ------------------------------------------------------------------ checks used by other modules

    /** True if the point is inside India (the boundary has a 3 km margin for coasts and islands). */
    public boolean inIndia(double lat, double lng) {
        return jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_SERVICEABILITY_IN_INDIA))
            .param("lat", lat).param("lng", lng)
            .query(Boolean.class).single();
    }

    /** The nearest live city whose service radius covers the point (does not check India; see {@link #inIndia}). */
    public Optional<CityDto> liveCityAt(double lat, double lng) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_SERVICEABILITY_LIVE_CITY_AT))
            .param("lat", lat).param("lng", lng)
            .query(CityDto.class)
            .optional();
    }

    /**
     * Checks a customer address: its PIN code must be served (that decides the city), and its map pin must be in
     * India and within that city's radius. Throws 422 PINCODE_NOT_SERVED, LOCATION_OUTSIDE_INDIA or
     * LOCATION_OUTSIDE_CITY.
     */
    public ServedAddress requireServedAddress(String pincode, double lat, double lng) {
        PincodeCheckDto pin = checkPincode(pincode);
        if (!pin.serviceable()) {
            throw ApiException.unprocessable("PINCODE_NOT_SERVED",
                "We don't serve PIN code " + pin.pincode() + " yet");
        }
        requireInIndia(lat, lng);
        boolean within = jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_SERVICEABILITY_WITHIN_CITY))
            .param("cityId", pin.cityId()).param("lat", lat).param("lng", lng)
            .query(Boolean.class).single();
        if (!within) {
            throw ApiException.unprocessable("LOCATION_OUTSIDE_CITY",
                "The map pin is too far from " + pin.cityName() + " for PIN code " + pin.pincode());
        }
        return new ServedAddress(pin.cityId(), pin.cityName(), pin.area());
    }

    /**
     * Checks a Genie's base location: in India and within a live city. Returns that city.
     * Throws 422 LOCATION_OUTSIDE_INDIA or CITY_NOT_LIVE.
     */
    public CityDto requireLiveCity(double lat, double lng) {
        requireInIndia(lat, lng);
        return liveCityAt(lat, lng).orElseThrow(() -> ApiException.unprocessable("CITY_NOT_LIVE",
            "ProGenie is not live at this location yet"));
    }

    public void requireInIndia(double lat, double lng) {
        if (!inIndia(lat, lng)) {
            throw ApiException.unprocessable("LOCATION_OUTSIDE_INDIA", "Pick a location in India");
        }
    }
}
