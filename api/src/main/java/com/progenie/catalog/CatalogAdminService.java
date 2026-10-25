package com.progenie.catalog;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import com.progenie.catalog.CatalogCaches.CatalogChanged;
import com.progenie.catalog.CatalogCaches.EvictsCatalog;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin maintenance of the catalog (categories, services) and of cities: PIN codes, pricing, go-live. */
@Service
public class CatalogAdminService {

    private static final String SLUG = "^[a-z0-9]+(-[a-z0-9]+)*$";

    public record CategoryRequest(@NotBlank @Pattern(regexp = SLUG) @Size(max = 60) String slug,
                                  @NotBlank @Size(max = 80) String name,
                                  @Size(max = 300) String description,
                                  @Size(max = 40) String icon,
                                  @Size(max = 500) String imageUrl,
                                  @Min(0) @Max(1000) int sortOrder,
                                  boolean active) {
    }

    public record ServiceRequest(@NotNull Long categoryId,
                                 @NotBlank @Pattern(regexp = SLUG) @Size(max = 80) String slug,
                                 @NotBlank @Size(max = 120) String name,
                                 @Size(max = 500) String description,
                                 @NotNull @DecimalMin("1") @DecimalMax("100000") BigDecimal basePrice,
                                 @Min(15) @Max(720) int durationMinutes,
                                 boolean active) {
    }

    public record AdminCategoryDto(long id, String slug, String name, @Nullable String description, @Nullable String icon, @Nullable String imageUrl,
                                   int sortOrder, boolean active, int serviceCount) {
    }

    public record AdminServiceDto(long id, long categoryId, String categoryName, String slug, String name,
                                  @Nullable String description, BigDecimal basePrice, int durationMinutes, boolean active,
                                  int genieCount) {
    }

    public record PricingRequest(@NotNull @DecimalMin("0") @DecimalMax("0.5") BigDecimal commissionRate,
                                 @NotNull @DecimalMin("0") @DecimalMax("50") BigDecimal travelFreeKm,
                                 @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal travelBaseFee,
                                 @NotNull @DecimalMin("0") @DecimalMax("200") BigDecimal travelPerKm,
                                 @NotNull @DecimalMin("0") @DecimalMax("5000") BigDecimal travelFeeCap) {
    }

    /** A city as admins see it: live or not, its centre and radius, how many PIN codes, and its pricing. */
    public record AdminCityDto(long cityId, String slug, String name, String state, boolean active, @Nullable Double centerLat,
                               @Nullable Double centerLng, BigDecimal radiusKm, int pincodeCount, BigDecimal commissionRate,
                               BigDecimal travelFreeKm, BigDecimal travelBaseFee, BigDecimal travelPerKm,
                               BigDecimal travelFeeCap, String currency) {
    }

    /** Create or edit a city. A new city starts switched off, with default pricing. */
    public record CityRequest(@NotBlank @Pattern(regexp = SLUG) @Size(max = 60) String slug,
                              @NotBlank @Size(max = 80) String name,
                              @NotBlank @Size(max = 80) String state,
                              @NotNull @DecimalMin("-90") @DecimalMax("90") Double centerLat,
                              @NotNull @DecimalMin("-180") @DecimalMax("180") Double centerLng,
                              @NotNull @DecimalMin("1") @DecimalMax("100") BigDecimal radiusKm) {
    }

    public record CityStatusRequest(boolean active) {
    }

    /** Add or edit a served PIN code. lat/lng (its rough centre) is optional. */
    public record PincodeRequest(@Size(max = 120) String area,
                                 @DecimalMin("-90") @DecimalMax("90") Double lat,
                                 @DecimalMin("-180") @DecimalMax("180") Double lng,
                                 boolean active) {
    }

    public record AdminPincodeDto(String pincode, @Nullable String area, @Nullable Double lat, @Nullable Double lng, boolean active) {
    }

    private record Readiness(boolean active, boolean hasPricing, int activePincodes) {
    }

    private final JdbcClient jdbc;
    private final ServiceabilityService serviceability;
    private final ApplicationEventPublisher events;

    public CatalogAdminService(JdbcClient jdbc, ServiceabilityService serviceability, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.serviceability = serviceability;
        this.events = events;
    }

    // ------------------------------------------------------------------ categories

    @Transactional(readOnly = true)
    public List<AdminCategoryDto> categories() {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_ADMIN_CATEGORIES))
            .query(AdminCategoryDto.class).list();
    }

    @Transactional
    @EvictsCatalog
    public AdminCategoryDto createCategory(CategoryRequest req) {
        events.publishEvent(new CatalogChanged("category"));
        try {
            long id = jdbc.sql(QueryUtil.get(QueryConstants.INSERT_CATALOG_CATEGORY))
                .params(categoryParams(req)).query(Long.class).single();
            return category(id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SLUG_TAKEN", "Another category already uses this slug");
        }
    }

    @Transactional
    @EvictsCatalog
    public AdminCategoryDto updateCategory(long id, CategoryRequest req) {
        events.publishEvent(new CatalogChanged("category"));
        try {
            int rows = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_CATALOG_CATEGORY))
                .params(categoryParams(req)).param("id", id).update();
            if (rows == 0) {
                throw ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found");
            }
            return category(id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SLUG_TAKEN", "Another category already uses this slug");
        }
    }

    private AdminCategoryDto category(long id) {
        return categories().stream().filter(c -> c.id() == id).findFirst()
            .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "Category not found"));
    }

    private static Map<String, Object> categoryParams(CategoryRequest req) {
        Map<String, Object> p = new HashMap<>();
        p.put("slug", req.slug());
        p.put("name", req.name().trim());
        p.put("description", req.description());
        p.put("icon", req.icon());
        p.put("image", req.imageUrl());
        p.put("sort", req.sortOrder());
        p.put("active", req.active());
        return p;
    }

    // ------------------------------------------------------------------ services

    @Transactional(readOnly = true)
    public List<AdminServiceDto> services(Long categoryId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_ADMIN_SERVICES))
            .param("c", categoryId).query(AdminServiceDto.class).list();
    }

    @Transactional
    @EvictsCatalog
    public AdminServiceDto createService(ServiceRequest req) {
        events.publishEvent(new CatalogChanged("service"));
        requireCategory(req.categoryId());
        try {
            long id = jdbc.sql(QueryUtil.get(QueryConstants.INSERT_CATALOG_SERVICE))
                .params(serviceParams(req)).query(Long.class).single();
            return service(id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SLUG_TAKEN", "Another service already uses this slug");
        }
    }

    /** Price changes apply to new bookings only; existing bookings keep their price snapshot. */
    @Transactional
    @EvictsCatalog
    public AdminServiceDto updateService(long id, ServiceRequest req) {
        events.publishEvent(new CatalogChanged("service"));
        requireCategory(req.categoryId());
        try {
            int rows = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_CATALOG_SERVICE))
                .params(serviceParams(req)).param("id", id).update();
            if (rows == 0) {
                throw ApiException.notFound("SERVICE_NOT_FOUND", "Service not found");
            }
            return service(id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SLUG_TAKEN", "Another service already uses this slug");
        }
    }

    private AdminServiceDto service(long id) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_ADMIN_SERVICE)).param("id", id).query(AdminServiceDto.class).optional()
            .orElseThrow(() -> ApiException.notFound("SERVICE_NOT_FOUND", "Service not found"));
    }

    private void requireCategory(long categoryId) {
        boolean exists = jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_CATALOG_CATEGORY))
            .param("id", categoryId).query(Boolean.class).single();
        if (!exists) {
            throw ApiException.badRequest("CATEGORY_NOT_FOUND", "Category not found");
        }
    }

    private static Map<String, Object> serviceParams(ServiceRequest req) {
        Map<String, Object> p = new HashMap<>();
        p.put("category", req.categoryId());
        p.put("slug", req.slug());
        p.put("name", req.name().trim());
        p.put("description", req.description());
        p.put("price", req.basePrice());
        p.put("duration", req.durationMinutes());
        p.put("active", req.active());
        return p;
    }

    // ------------------------------------------------------------------ cities

    @Transactional(readOnly = true)
    public List<AdminCityDto> cities() {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_ADMIN_CITIES))
            .query(AdminCityDto.class).list();
    }

    /** Opens a city as data: it starts switched off with default pricing; add PIN codes, then switch it on. */
    @Transactional
    @EvictsCatalog
    public AdminCityDto createCity(CityRequest req) {
        events.publishEvent(new CatalogChanged("city"));
        serviceability.requireInIndia(req.centerLat(), req.centerLng());
        long id;
        try {
            id = jdbc.sql(QueryUtil.get(QueryConstants.INSERT_CATALOG_CITY)).params(cityParams(req))
                .query(Long.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_CITY", "A city with this slug or name already exists");
        }
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_CATALOG_CITY_DEFAULT_PRICING)).param("id", id).update();
        return city(id);
    }

    @Transactional
    @EvictsCatalog
    public AdminCityDto updateCity(long id, CityRequest req) {
        events.publishEvent(new CatalogChanged("city"));
        requireCity(id);
        serviceability.requireInIndia(req.centerLat(), req.centerLng());
        Map<String, Object> p = cityParams(req);
        p.put("id", id);
        try {
            jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_CATALOG_CITY)).params(p).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("DUPLICATE_CITY", "A city with this slug or name already exists");
        }
        return city(id);
    }

    /**
     * Switches a city on or off. Going live needs pricing and at least one active PIN code; the last live city
     * cannot be switched off. Existing addresses and bookings in a switched-off city are kept.
     */
    @Transactional
    @EvictsCatalog
    public AdminCityDto setCityActive(long id, boolean active) {
        events.publishEvent(new CatalogChanged("city"));
        Readiness r = readiness(id);
        if (r.active() == active) {
            return city(id);
        }
        if (active && !r.hasPricing()) {
            throw ApiException.unprocessable("CITY_NOT_READY", "Set this city's pricing before switching it on");
        }
        if (active && r.activePincodes() == 0) {
            throw ApiException.unprocessable("CITY_NOT_READY", "Add at least one PIN code before switching it on");
        }
        if (!active && liveCityCount() <= 1) {
            throw ApiException.unprocessable("LAST_LIVE_CITY", "At least one city must stay live");
        }
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_CATALOG_CITY_ACTIVE)).param("active", active).param("id", id)
            .update();
        return city(id);
    }

    @Transactional(readOnly = true)
    public List<AdminPincodeDto> pincodes(long cityId) {
        requireCity(cityId);
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_CITY_PINCODES)).param("id", cityId)
            .query(AdminPincodeDto.class).list();
    }

    /** Adds or edits a served PIN code. A PIN code belongs to one city only. */
    @Transactional
    @EvictsCatalog
    public AdminPincodeDto savePincode(long cityId, String pincode, PincodeRequest req) {
        events.publishEvent(new CatalogChanged("pincode"));
        requireCity(cityId);
        if (pincode == null || !pincode.matches(ServiceabilityService.PINCODE)) {
            throw ApiException.badRequest("INVALID_PINCODE", "Enter a valid 6-digit PIN code");
        }
        Long owner = jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_PINCODE_OWNER)).param("pincode", pincode)
            .query(Long.class).optional().orElse(null);
        if (owner != null && owner != cityId) {
            throw ApiException.conflict("PINCODE_TAKEN", "PIN code " + pincode + " already belongs to another city");
        }
        if ((req.lat() == null) != (req.lng() == null)) {
            throw ApiException.badRequest("INVALID_LOCATION", "Give both latitude and longitude, or neither");
        }
        if (req.lat() != null) {
            serviceability.requireInIndia(req.lat(), req.lng());
        }
        if (owner != null && !req.active()) {
            requireOtherActivePincode(cityId, pincode);
        }
        Map<String, Object> p = new HashMap<>();
        p.put("pincode", pincode);
        p.put("id", cityId);
        p.put("area", trimToNull(req.area()));
        p.put("lat", req.lat());
        p.put("lng", req.lng());
        p.put("active", req.active());
        jdbc.sql(QueryUtil.get(QueryConstants.UPSERT_CATALOG_CITY_PINCODE)).params(p).update();
        return pincodes(cityId).stream().filter(x -> x.pincode().equals(pincode)).findFirst().orElseThrow();
    }

    @Transactional
    @EvictsCatalog
    public void deletePincode(long cityId, String pincode) {
        events.publishEvent(new CatalogChanged("pincode"));
        requireCity(cityId);
        requireOtherActivePincode(cityId, pincode);
        int removed = jdbc.sql(QueryUtil.get(QueryConstants.DELETE_CATALOG_CITY_PINCODE))
            .param("pincode", pincode).param("id", cityId).update();
        if (removed == 0) {
            throw ApiException.notFound("PINCODE_NOT_FOUND", "PIN code " + pincode + " is not in this city");
        }
    }

    /** A live city must keep at least one active PIN code, or nobody there could save an address. */
    private void requireOtherActivePincode(long cityId, String pincode) {
        Readiness r = readiness(cityId);
        boolean thisOneActive = pincodes(cityId).stream()
            .anyMatch(x -> x.pincode().equals(pincode) && x.active());
        if (r.active() && thisOneActive && r.activePincodes() <= 1) {
            throw ApiException.unprocessable("CITY_NEEDS_PINCODE",
                "A live city needs at least one active PIN code; switch the city off first");
        }
    }

    private AdminCityDto city(long id) {
        return cities().stream().filter(c -> c.cityId() == id).findFirst()
            .orElseThrow(() -> ApiException.notFound("CITY_NOT_FOUND", "City not found"));
    }

    private void requireCity(long id) {
        boolean exists = jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_CATALOG_CITY)).param("id", id)
            .query(Boolean.class).single();
        if (!exists) {
            throw ApiException.notFound("CITY_NOT_FOUND", "City not found");
        }
    }

    private Readiness readiness(long id) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_CITY_READINESS)).param("id", id)
            .query(Readiness.class).optional()
            .orElseThrow(() -> ApiException.notFound("CITY_NOT_FOUND", "City not found"));
    }

    private int liveCityCount() {
        return jdbc.sql(QueryUtil.get(QueryConstants.COUNT_CATALOG_ACTIVE_CITIES)).query(Integer.class).single();
    }

    private static Map<String, Object> cityParams(CityRequest req) {
        Map<String, Object> p = new HashMap<>();
        p.put("slug", req.slug());
        p.put("name", req.name().trim());
        p.put("state", req.state().trim());
        p.put("lat", req.centerLat());
        p.put("lng", req.centerLng());
        p.put("radius", req.radiusKm());
        return p;
    }

    // ------------------------------------------------------------------ city pricing

    @Transactional
    @EvictsCatalog
    public AdminCityDto updatePricing(long cityId, PricingRequest req) {
        events.publishEvent(new CatalogChanged("pricing"));
        requireCity(cityId);
        jdbc.sql(QueryUtil.get(QueryConstants.UPSERT_CATALOG_CITY_PRICING))
            .param("id", cityId).param("rate", req.commissionRate()).param("free", req.travelFreeKm())
            .param("base", req.travelBaseFee()).param("perKm", req.travelPerKm()).param("cap", req.travelFeeCap())
            .update();
        return city(cityId);
    }

    private static String trimToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
