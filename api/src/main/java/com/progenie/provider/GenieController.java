package com.progenie.provider;

import java.util.List;
import java.util.UUID;

import com.progenie.demand.DemandRecorder;
import com.progenie.provider.GenieDtos.GenieCardDto;
import com.progenie.shared.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import com.progenie.provider.GenieDtos.GenieDetailDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public Genie listing: GET /api/v1/genies?category=electrician&sort=RATING */
@RestController
@RequestMapping("/api/v1/genies")
public class GenieController {

    private final GenieQueryService genies;

    private final DemandRecorder demand;

    public GenieController(GenieQueryService genies, DemandRecorder demand) {
        this.genies = genies;
        this.demand = demand;
    }

    /** Example: GET /api/v1/genies?city=ahmedabad&category=plumbing&sort=PRICE */
    @GetMapping
    public List<GenieCardDto> list(@RequestParam(required = false) String city,
                                   @RequestParam(required = false) String category,
                                   @RequestParam(required = false) Long serviceId,
                                   @RequestParam(defaultValue = "RATING") GenieQueryService.Sort sort,
                                   @RequestParam(defaultValue = "20") int limit,
                                   HttpServletRequest request) {
        List<GenieCardDto> list = genies.list(blankToNull(city), category, serviceId, sort, limit);
        if (list.isEmpty() && blankToNull(city) != null && (category != null || serviceId != null)) {
            // a category or service page with no Genie in this city: unmet demand (3D)
            demand.recordNoGenieListed(city.trim(), category, serviceId, CurrentUser.idOrNull(), DemandRecorder.who(request));
        }
        return list;
    }

    @GetMapping("/{id}")
    public GenieDetailDto detail(@PathVariable UUID id) {
        return genies.detail(id);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
