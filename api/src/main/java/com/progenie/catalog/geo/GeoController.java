package com.progenie.catalog.geo;

import com.progenie.catalog.CatalogDtos.PincodeAtDto;
import com.progenie.catalog.CatalogDtos.PincodeLocationDto;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PIN code ↔ map point, for the address form (login needed: answers can come from a rate-limited outside service).
 */
@RestController
@RequestMapping("/api/v1/geo")
public class GeoController {

    private final GeoLookupService geo;

    public GeoController(GeoLookupService geo) {
        this.geo = geo;
    }

    /** Example: GET /api/v1/geo/pincodes/380009 → its rough centre. */
    @GetMapping("/pincodes/{pincode}")
    public PincodeLocationDto locate(@PathVariable String pincode) {
        return geo.locate(pincode);
    }

    /** Example: GET /api/v1/geo/pincode-at?lat=23.035&lng=72.561 → the PIN code there. */
    @GetMapping("/pincode-at")
    public PincodeAtDto pincodeAt(@RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                                  @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng) {
        return geo.pincodeAt(lat, lng);
    }
}
