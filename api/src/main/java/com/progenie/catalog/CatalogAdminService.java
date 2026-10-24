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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin maintenance of the catalog (categories, services) and per-city pricing. */
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

    public record AdminCategoryDto(long id, String slug, String name, String description, String icon, String imageUrl,
                                   int sortOrder, boolean active, int serviceCount) {
    }

    public record AdminServiceDto(long id, long categoryId, String categoryName, String slug, String name,
                                  String description, BigDecimal basePrice, int durationMinutes, boolean active,
                                  int genieCount) {
    }

    public record PricingRequest(@NotNull @DecimalMin("0") @DecimalMax("0.5") BigDecimal commissionRate,
                                 @NotNull @DecimalMin("0") @DecimalMax("50") BigDecimal travelFreeKm,
                                 @NotNull @DecimalMin("0") @DecimalMax("1000") BigDecimal travelBaseFee,
                                 @NotNull @DecimalMin("0") @DecimalMax("200") BigDecimal travelPerKm,
                                 @NotNull @DecimalMin("0") @DecimalMax("5000") BigDecimal travelFeeCap) {
    }

    public record CityPricingDto(long cityId, String name, String state, boolean active, BigDecimal commissionRate,
                                 BigDecimal travelFreeKm, BigDecimal travelBaseFee, BigDecimal travelPerKm,
                                 BigDecimal travelFeeCap, String currency) {
    }

    private final JdbcClient jdbc;

    public CatalogAdminService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ categories

    @Transactional(readOnly = true)
    public List<AdminCategoryDto> categories() {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_ADMIN_CATEGORIES))
            .query(AdminCategoryDto.class).list();
    }

    @Transactional
    public AdminCategoryDto createCategory(CategoryRequest req) {
        try {
            long id = jdbc.sql(QueryUtil.get(QueryConstants.INSERT_CATALOG_CATEGORY))
                .params(categoryParams(req)).query(Long.class).single();
            return category(id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SLUG_TAKEN", "Another category already uses this slug");
        }
    }

    @Transactional
    public AdminCategoryDto updateCategory(long id, CategoryRequest req) {
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
    public AdminServiceDto createService(ServiceRequest req) {
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
    public AdminServiceDto updateService(long id, ServiceRequest req) {
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

    // ------------------------------------------------------------------ city pricing

    @Transactional(readOnly = true)
    public List<CityPricingDto> cityPricing() {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_CITY_PRICING))
            .query(CityPricingDto.class).list();
    }

    @Transactional
    public CityPricingDto updatePricing(long cityId, PricingRequest req) {
        boolean exists = jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_CATALOG_CITY)).param("id", cityId)
            .query(Boolean.class).single();
        if (!exists) {
            throw ApiException.notFound("CITY_NOT_FOUND", "City not found");
        }
        jdbc.sql(QueryUtil.get(QueryConstants.UPSERT_CATALOG_CITY_PRICING))
            .param("id", cityId).param("rate", req.commissionRate()).param("free", req.travelFreeKm())
            .param("base", req.travelBaseFee()).param("perKm", req.travelPerKm()).param("cap", req.travelFeeCap())
            .update();
        return cityPricing().stream().filter(c -> c.cityId() == cityId).findFirst().orElseThrow();
    }
}
