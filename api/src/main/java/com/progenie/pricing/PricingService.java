package com.progenie.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.progenie.pricing.PriceCalculator.CityPricing;
import com.progenie.pricing.PriceCalculator.DistanceMethod;
import com.progenie.pricing.PriceCalculator.PriceBreakdown;
import com.progenie.pricing.PriceCalculator.ServicePrice;
import com.progenie.pricing.distance.DistanceService;
import com.progenie.pricing.distance.DistanceService.TravelDistance;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.ServiceLines;
import com.progenie.shared.util.ServiceLines.ServiceLine;
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
                               BigDecimal travelFeeCap, BigDecimal inspectionFee) {
    }

    /** The travel part of a price (Phase 3.5D: a quoted job), and the city's commission rate. */
    public record Travel(BigDecimal distanceKm, DistanceMethod method, BigDecimal travelFee, BigDecimal commissionRate) {
    }

    public PriceBreakdown estimate(UUID genieId, long serviceId, double lat, double lng, BigDecimal tip) {
        return estimate(genieId, List.of(new ServiceLine(serviceId, 1)), lat, lng, tip);
    }

    /**
     * Several services from one Genie (Phase 3.5C): each line at the Genie's price × quantity, one travel fee.
     * 422 SERVICE_NOT_OFFERED when the Genie doesn't offer one of them, JOB_TOO_LONG over 8 hours together.
     */
    public PriceBreakdown estimate(UUID genieId, List<ServiceLine> lines, double lat, double lng, BigDecimal tip) {
        List<ServicePrice> prices = servicePrices(genieId, lines);
        QuoteInputs in = jdbc.sql(QueryUtil.get(QueryConstants.GET_PRICING_ESTIMATE))
            .param("genieId", genieId)
            .param("serviceId", lines.getFirst().serviceId())
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
        List<Integer> quantities = lines.stream().map(ServiceLine::quantity).toList();
        if (prices.stream().anyMatch(ServicePrice::inspectionFirst)) {
            return PriceCalculator.inspection(prices, quantities, in.inspectionFee(), d.km(), d.method(), tip, pricing);
        }
        return PriceCalculator.quote(prices, quantities, d.km(), d.method(), tip, pricing);
    }

    /**
     * Distance, travel fee and commission rate from a Genie's base to a point (Phase 3.5D: a job made from a quote is
     * priced by the quote; only the travel fee comes from here).
     */
    public Travel travel(UUID genieId, double lat, double lng) {
        QuoteInputs in = jdbc.sql(QueryUtil.get(QueryConstants.GET_PRICING_TRAVEL_INPUTS))
            .param("genieId", genieId).param("lat", lat).param("lng", lng)
            .query(QuoteInputs.class).optional()
            .orElseThrow(() -> ApiException.notFound("QUOTE_UNAVAILABLE", "This Genie can't be priced right now"));
        if (in.straightKm() == null || in.genieLat() == null || in.genieLng() == null) {
            throw ApiException.badRequest("GENIE_LOCATION_MISSING", "This Genie has not set a base location yet");
        }
        CityPricing pricing = new CityPricing(in.commissionRate(), in.travelFreeKm(), in.travelBaseFee(),
            in.travelPerKm(), in.travelFeeCap());
        TravelDistance d = distances.travel(in.genieLat(), in.genieLng(), lat, lng, in.straightKm());
        BigDecimal km = d.km().setScale(2, RoundingMode.HALF_UP);
        return new Travel(km, d.method(), PriceCalculator.travelFee(km, pricing), in.commissionRate());
    }

    /**
     * The Genie's price and duration for each line's service, in the lines' order. 422 SERVICE_NOT_OFFERED when the
     * Genie doesn't offer one (or it is switched off), JOB_TOO_LONG when the lines take over 8 hours together.
     */
    public List<ServicePrice> servicePrices(UUID genieId, List<ServiceLine> lines) {
        Map<Long, ServicePrice> offered = jdbc.sql(QueryUtil.get(QueryConstants.GET_PRICING_SERVICE_PRICES))
            .param("genieId", genieId)
            .param("ids", lines.stream().map(ServiceLine::serviceId).toList())
            .query(ServicePrice.class).list().stream()
            .collect(Collectors.toMap(ServicePrice::serviceId, Function.identity()));
        List<ServicePrice> prices = lines.stream().map(l -> {
            ServicePrice p = offered.get(l.serviceId());
            if (p == null) {
                throw ApiException.unprocessable("SERVICE_NOT_OFFERED", "This Genie does not offer one of those services");
            }
            return p;
        }).toList();
        int minutes = 0;
        for (int i = 0; i < lines.size(); i++) {
            minutes += prices.get(i).durationMinutes() * lines.get(i).quantity();
        }
        boolean inspection = prices.stream().anyMatch(ServicePrice::inspectionFirst);
        if (!inspection && minutes > ServiceLines.MAX_MINUTES) {
            throw ApiException.unprocessable("JOB_TOO_LONG", "That's more than 8 hours of work. Please split it into two bookings");
        }
        return prices;
    }

    /** Minutes the lines take together (the slot length); an inspection visit (Phase 3.5D) takes 30. */
    public int durationMinutes(UUID genieId, List<ServiceLine> lines) {
        List<ServicePrice> prices = servicePrices(genieId, lines);
        if (prices.stream().anyMatch(ServicePrice::inspectionFirst)) {
            return PriceCalculator.INSPECTION_MINUTES;
        }
        int minutes = 0;
        for (int i = 0; i < lines.size(); i++) {
            minutes += prices.get(i).durationMinutes() * lines.get(i).quantity();
        }
        return minutes;
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
