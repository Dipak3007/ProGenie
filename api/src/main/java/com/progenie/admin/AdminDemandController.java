package com.progenie.admin;

import com.progenie.demand.DemandMap;
import com.progenie.demand.DemandMap.DemandMapDto;
import com.progenie.demand.DemandMap.Layer;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The demand heatmap (role ADMIN, Phase 3D): where customers book, and where they wanted something we couldn't give. */
@RestController
@RequestMapping("/api/v1/admin/demand")
public class AdminDemandController {

    private final DemandMap map;

    public AdminDemandController(DemandMap map) {
        this.map = map;
    }

    /** Example: GET /api/v1/admin/demand?layer=UNMET&days=30&cityId=1&hour=19 */
    @GetMapping
    public DemandMapDto map(@RequestParam(defaultValue = "BOOKINGS") Layer layer,
                            @RequestParam(defaultValue = "30") int days,
                            @RequestParam(required = false) @Nullable Long cityId,
                            @RequestParam(required = false) @Nullable Long categoryId,
                            @RequestParam(required = false) @Nullable Integer weekday,
                            @RequestParam(required = false) @Nullable Integer hour) {
        return map.map(layer, days, cityId, categoryId, weekday, hour);
    }
}
