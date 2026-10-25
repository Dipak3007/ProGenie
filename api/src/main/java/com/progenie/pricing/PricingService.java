package com.progenie.pricing;

import java.math.BigDecimal;
import java.util.UUID;

import com.progenie.pricing.PriceCalculator.CityPricing;
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
}
