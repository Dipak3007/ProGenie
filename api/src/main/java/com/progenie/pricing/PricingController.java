package com.progenie.pricing;

import java.math.BigDecimal;
import java.util.UUID;

import com.progenie.pricing.PriceCalculator.PriceBreakdown;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public price estimate so guests can see the full price before logging in. */
@RestController
public class PricingController {

    private final PricingService pricing;

    public PricingController(PricingService pricing) {
        this.pricing = pricing;
    }

    /** Example: GET /api/v1/pricing/estimate?genieId=...&serviceId=1&lat=23.03&lng=72.51&tip=50 */
    @GetMapping("/api/v1/pricing/estimate")
    public PriceBreakdown estimate(@RequestParam UUID genieId,
                                   @RequestParam long serviceId,
                                   @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                                   @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
                                   @RequestParam(required = false) @DecimalMin("0") @DecimalMax("5000") BigDecimal tip) {
        return pricing.estimate(genieId, serviceId, lat, lng, tip);
    }
}
