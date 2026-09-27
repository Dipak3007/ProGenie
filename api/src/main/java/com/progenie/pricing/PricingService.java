package com.progenie.pricing;

import java.math.BigDecimal;
import java.util.UUID;

import com.progenie.pricing.PriceCalculator.CityPricing;
import com.progenie.pricing.PriceCalculator.PriceBreakdown;
import com.progenie.shared.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Builds a price estimate for a Genie + service + customer location.
 *
 * <p>Distance today = straight-line distance (PostGIS) × 1.3 road factor, from the Genie's base location.
 * Phase 1 swaps in OSRM road distance and the live-location rule for instant bookings, and signs the
 * quote so the booking endpoint can trust it.
 */
@Service
public class PricingService {

    static final BigDecimal ROAD_FACTOR = new BigDecimal("1.3");

    private final JdbcClient jdbc;

    public PricingService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record QuoteInputs(BigDecimal servicePrice, BigDecimal straightKm, BigDecimal commissionRate,
                               BigDecimal travelFreeKm, BigDecimal travelBaseFee, BigDecimal travelPerKm,
                               BigDecimal travelFeeCap) {
    }

    public PriceBreakdown estimate(UUID genieId, long serviceId, double lat, double lng, BigDecimal tip) {
        QuoteInputs in = jdbc.sql("""
                SELECT coalesce(gs.price_override, s.base_price) AS service_price,
                       (ST_Distance(gp.base_location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography) / 1000.0)::numeric(8,2)
                                                               AS straight_km,
                       cp.commission_rate, cp.travel_free_km, cp.travel_base_fee, cp.travel_per_km, cp.travel_fee_cap
                  FROM genie_profiles gp
                  JOIN genie_services gs      ON gs.genie_id = gp.user_id AND gs.service_id = :serviceId AND gs.is_active
                  JOIN services s             ON s.id = gs.service_id
                  JOIN genie_service_areas ga ON ga.genie_id = gp.user_id
                  JOIN city_pricing cp        ON cp.city_id = ga.city_id
                 WHERE gp.user_id = :genieId AND gp.verification_status = 'APPROVED'
                 LIMIT 1
                """)
            .param("genieId", genieId)
            .param("serviceId", serviceId)
            .param("lat", lat)
            .param("lng", lng)
            .query(QuoteInputs.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("QUOTE_UNAVAILABLE", "This Genie does not offer that service"));

        if (in.straightKm() == null) {
            throw ApiException.badRequest("GENIE_LOCATION_MISSING", "This Genie has not set a base location yet");
        }
        CityPricing pricing = new CityPricing(in.commissionRate(), in.travelFreeKm(), in.travelBaseFee(),
            in.travelPerKm(), in.travelFeeCap());
        return PriceCalculator.quote(in.servicePrice(), in.straightKm().multiply(ROAD_FACTOR), tip, pricing);
    }
}
