package com.progenie.catalog;

import java.util.List;

import com.progenie.catalog.CatalogDtos.CategoryDetailDto;
import com.progenie.catalog.CatalogDtos.CategoryDto;
import com.progenie.catalog.CatalogDtos.CityDto;
import com.progenie.catalog.CatalogDtos.SearchHitDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public catalog endpoints: anyone can browse without logging in. */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogQueryService catalog;

    public CatalogController(CatalogQueryService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/cities")
    public List<CityDto> cities() {
        return catalog.cities();
    }

    @GetMapping("/categories")
    public List<CategoryDto> categories() {
        return catalog.categories();
    }

    /** Example: GET /api/v1/search?q=fan */
    @GetMapping("/search")
    public List<SearchHitDto> search(@RequestParam String q, @RequestParam(defaultValue = "8") int limit) {
        return catalog.search(q, limit);
    }

    @GetMapping("/categories/{slug}")
    public CategoryDetailDto category(@PathVariable String slug) {
        return catalog.category(slug);
    }
}
