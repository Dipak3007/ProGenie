package com.progenie.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import com.progenie.pricing.PriceCalculator.CityPricing;
import com.progenie.pricing.PriceCalculator.DistanceMethod;
import com.progenie.pricing.PriceCalculator.PriceBreakdown;
import com.progenie.pricing.distance.DistanceService;
import com.progenie.pricing.distance.DistanceService.TravelDistance;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Builds a price estimate for a Genie + service + customer location.
 *
 * <p>Distance = from the Genie's base location to the customer: road distance when an OSRM server is configured,
 * else straight line (PostGIS) × road factor 1.3 ({@link DistanceService}). The booking recomputes the quote the
 * same way, and routes are cached, so it charges what the estimate showed.
 */
@Service
public class PricingService {

    private final JdbcClient jdbc;
    private final DistanceService distances;

    public PricingService(JdbcClient jdbc, DistanceService distances) {
        this.jdbc = jdbc;
        this.distances = distances;
    }

    record QuoteInputs(BigDecimal servicePrice, Double genieLat, Double genieLng, BigDecimal straightKm, BigDecimal commissionRate,
                               BigDecimal travelFreeKm, BigDecimal travelBaseFee, BigDecimal travelPerKm,
                               BigDecimal travelFeeCap) {
    }

    public PriceBreakdown estimate(UUID genieId, long serviceId, double lat, double lng, BigDecimal tip) {
        QuoteInputs in = jdbc.sql(QueryUtil.get(QueryConstants.GET_PRICING_ESTIMATE))
            .param("genieId", genieId)
            .param("serviceId", serviceId)
            .param("lat", lat)
            .param("lng", lng)
            .query(QuoteInputs.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("QUOTE_UNAVAILABLE", "This Genie does not offer that service"));

        if (in.straightKm() == null || in.genieLat() == null || in.genieLng() == null) {
            throw ApiException.badRequest("GENIE_LOCATION_MISSING", "This Genie has not set a base location yet");
        }
        CityPricing pricing = new CityPricing(in.commissionRate(), in.travelFreeKm(), in.travelBaseFee(),
            in.travelPerKm(), in.travelFeeCap());
        TravelDistance d = distances.travel(in.genieLat(), in.genieLng(), lat, lng, in.straightKm());
        return PriceCalculator.quote(in.servicePrice(), d.km(), d.method(), tip, pricing);
    }

    /** The city's Genie Now fee (Phase 3C-9): paid by the customer on an instant booking, earned by the Genie. 0 if unset. */
    public BigDecimal instantFee(long cityId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_PRICING_INSTANT_FEE)).param("cityId", cityId)
            .query(BigDecimal.class).optional().orElse(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    /** A distance between two points, and how it was measured (road, or straight line × road factor). */
    public record Trip(BigDecimal km, DistanceMethod method) {
    }

    /**
     * Distance between any two points (live tracking: the Genie's position to the customer, Phase 3C), measured the
     * same way as the travel fee: OSRM road distance when configured, else the straight line × 1.3.
     */
    public Trip distanceBetween(double fromLat, double fromLng, double toLat, double toLng) {
        TravelDistance d = distances.travel(fromLat, fromLng, toLat, toLng, straightKm(fromLat, fromLng, toLat, toLng));
        return new Trip(d.km(), d.method());
    }

    /** Great-circle distance in km (haversine), 2 decimals. */
    static BigDecimal straightKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0088;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return BigDecimal.valueOf(2 * r * Math.asin(Math.min(1, Math.sqrt(a)))).setScale(2, RoundingMode.HALF_UP);
    }
}
