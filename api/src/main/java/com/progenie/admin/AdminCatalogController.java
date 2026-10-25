package com.progenie.admin;

import java.util.List;

import com.progenie.catalog.CatalogAdminService;
import com.progenie.catalog.CatalogAdminService.AdminCategoryDto;
import com.progenie.catalog.CatalogAdminService.AdminServiceDto;
import com.progenie.catalog.CatalogAdminService.CategoryRequest;
import com.progenie.catalog.CatalogAdminService.AdminCityDto;
import com.progenie.catalog.CatalogAdminService.AdminPincodeDto;
import com.progenie.catalog.CatalogAdminService.CityRequest;
import com.progenie.catalog.CatalogAdminService.CityStatusRequest;
import com.progenie.catalog.CatalogAdminService.PincodeRequest;
import com.progenie.catalog.CatalogAdminService.PricingRequest;
import com.progenie.catalog.CatalogAdminService.ServiceRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Catalog and city maintenance (role ADMIN). Inactive items are hidden from customers.
 * Opening a city is data only: POST /cities (starts off), PUT its PIN codes and pricing, then PUT /cities/{id}/status.
 */
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
    public List<AdminCityDto> cities() {
        return catalog.cities();
    }

    @PostMapping("/cities")
    @ResponseStatus(HttpStatus.CREATED)
    public AdminCityDto createCity(@Valid @RequestBody CityRequest req) {
        return catalog.createCity(req);
    }

    @PutMapping("/cities/{id}")
    public AdminCityDto updateCity(@PathVariable long id, @Valid @RequestBody CityRequest req) {
        return catalog.updateCity(id, req);
    }

    @PutMapping("/cities/{id}/status")
    public AdminCityDto cityStatus(@PathVariable long id, @RequestBody CityStatusRequest req) {
        return catalog.setCityActive(id, req.active());
    }

    @PutMapping("/cities/{id}/pricing")
    public AdminCityDto pricing(@PathVariable long id, @Valid @RequestBody PricingRequest req) {
        return catalog.updatePricing(id, req);
    }

    @GetMapping("/cities/{id}/pincodes")
    public List<AdminPincodeDto> pincodes(@PathVariable long id) {
        return catalog.pincodes(id);
    }

    @PutMapping("/cities/{id}/pincodes/{pincode}")
    public AdminPincodeDto savePincode(@PathVariable long id, @PathVariable String pincode,
                                       @Valid @RequestBody PincodeRequest req) {
        return catalog.savePincode(id, pincode, req);
    }

    @DeleteMapping("/cities/{id}/pincodes/{pincode}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePincode(@PathVariable long id, @PathVariable String pincode) {
        catalog.deletePincode(id, pincode);
    }
}
