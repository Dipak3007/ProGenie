package com.progenie.catalog;

import java.util.List;

import com.progenie.catalog.CatalogDtos.AreaDto;
import com.progenie.catalog.CatalogDtos.CityDto;
import com.progenie.catalog.CatalogDtos.LocationCheckDto;
import com.progenie.catalog.CatalogDtos.PincodeCheckDto;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public: which PIN codes, places and cities ProGenie serves. No login needed. */
@RestController
@RequestMapping("/api/v1")
public class ServiceabilityController {

    private final ServiceabilityService serviceability;

    public ServiceabilityController(ServiceabilityService serviceability) {
        this.serviceability = serviceability;
    }

    /** Example: GET /api/v1/serviceability?pincode=380009 */
    @GetMapping("/serviceability")
    public PincodeCheckDto pincode(@RequestParam String pincode) {
        return serviceability.checkPincode(pincode);
    }

    /** Example: GET /api/v1/serviceability/location?lat=23.03&lng=72.56 */
    @GetMapping("/serviceability/location")
    public LocationCheckDto location(@RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                                     @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng) {
        return serviceability.checkLocation(lat, lng);
    }

    @GetMapping("/cities/{slug}")
    public CityDto city(@PathVariable String slug) {
        return serviceability.city(slug);
    }

    /** The served PIN codes of a live city, with area names (for pickers and price estimates). */
    @GetMapping("/cities/{slug}/areas")
    public List<AreaDto> areas(@PathVariable String slug) {
        return serviceability.areas(slug);
    }
}
