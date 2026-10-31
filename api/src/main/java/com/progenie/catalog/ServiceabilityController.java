package com.progenie.catalog;

import java.util.List;

import com.progenie.catalog.CatalogDtos.AreaDto;
import com.progenie.catalog.CatalogDtos.CityDto;
import com.progenie.catalog.CatalogDtos.LocationCheckDto;
import com.progenie.catalog.CatalogDtos.PincodeCheckDto;
import com.progenie.demand.DemandRecorder;
import jakarta.servlet.http.HttpServletRequest;
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
    private final DemandRecorder demand;

    public ServiceabilityController(ServiceabilityService serviceability, DemandRecorder demand) {
        this.serviceability = serviceability;
        this.demand = demand;
    }

    /** Example: GET /api/v1/serviceability?pincode=380009. An unserved PIN code counts as unmet demand (3D). */
    @GetMapping("/serviceability")
    public PincodeCheckDto pincode(@RequestParam String pincode, HttpServletRequest request) {
        PincodeCheckDto answer = serviceability.checkPincode(pincode);
        if (!answer.serviceable()) {
            demand.record(DemandRecorder.Event.pincode(answer.pincode()), DemandRecorder.who(request));
        }
        return answer;
    }

    /** Example: GET /api/v1/serviceability/location?lat=23.03&lng=72.56 */
    @GetMapping("/serviceability/location")
    public LocationCheckDto location(@RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
                                     @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
                                     HttpServletRequest request) {
        LocationCheckDto answer = serviceability.checkLocation(lat, lng);
        if (answer.inIndia() && !answer.serviceable()) {   // a place in India outside every live city: unmet demand
            demand.record(DemandRecorder.Event.location(lat, lng), DemandRecorder.who(request));
        }
        return answer;
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
