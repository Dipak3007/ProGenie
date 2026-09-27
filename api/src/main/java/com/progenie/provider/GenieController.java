package com.progenie.provider;

import java.util.List;
import java.util.UUID;

import com.progenie.provider.GenieDtos.GenieCardDto;
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

    public GenieController(GenieQueryService genies) {
        this.genies = genies;
    }

    @GetMapping
    public List<GenieCardDto> list(@RequestParam(required = false) String category,
                                   @RequestParam(required = false) Long serviceId,
                                   @RequestParam(defaultValue = "RATING") GenieQueryService.Sort sort,
                                   @RequestParam(defaultValue = "20") int limit) {
        return genies.list(category, serviceId, sort, limit);
    }

    @GetMapping("/{id}")
    public GenieDetailDto detail(@PathVariable UUID id) {
        return genies.detail(id);
    }
}
