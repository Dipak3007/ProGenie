package com.progenie.admin;

import java.util.List;

import com.progenie.catalog.CatalogAdminService;
import com.progenie.catalog.CatalogAdminService.AdminCategoryDto;
import com.progenie.catalog.CatalogAdminService.AdminServiceDto;
import com.progenie.catalog.CatalogAdminService.CategoryRequest;
import com.progenie.catalog.CatalogAdminService.CityPricingDto;
import com.progenie.catalog.CatalogAdminService.PricingRequest;
import com.progenie.catalog.CatalogAdminService.ServiceRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Catalog and per-city pricing maintenance (role ADMIN). Inactive items are hidden from customers. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminCatalogController {

    private final CatalogAdminService catalog;

    public AdminCatalogController(CatalogAdminService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/categories")
    public List<AdminCategoryDto> categories() {
        return catalog.categories();
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminCategoryDto createCategory(@Valid @RequestBody CategoryRequest req) {
        return catalog.createCategory(req);
    }

    @PutMapping("/categories/{id}")
    public AdminCategoryDto updateCategory(@PathVariable long id, @Valid @RequestBody CategoryRequest req) {
        return catalog.updateCategory(id, req);
    }

    @GetMapping("/services")
    public List<AdminServiceDto> services(@RequestParam(required = false) Long categoryId) {
        return catalog.services(categoryId);
    }

    @PostMapping("/services")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminServiceDto createService(@Valid @RequestBody ServiceRequest req) {
        return catalog.createService(req);
    }

    @PutMapping("/services/{id}")
    public AdminServiceDto updateService(@PathVariable long id, @Valid @RequestBody ServiceRequest req) {
        return catalog.updateService(id, req);
    }

    @GetMapping("/cities")
    public List<CityPricingDto> cities() {
        return catalog.cityPricing();
    }

    @PutMapping("/cities/{id}/pricing")
    public CityPricingDto pricing(@PathVariable long id, @Valid @RequestBody PricingRequest req) {
        return catalog.updatePricing(id, req);
    }
}
