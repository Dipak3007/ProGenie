package com.progenie.catalog;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.progenie.shared.error.ApiException;
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
        return jdbc.sql("""
                SELECT c.id, c.slug, c.name, c.description, c.icon, c.image_url, c.sort_order, c.is_active AS active,
                       (SELECT count(*) FROM services s WHERE s.category_id = c.id) AS service_count
                  FROM service_categories c ORDER BY c.sort_order, c.name
                """)
            .query(AdminCategoryDto.class).list();
    }

    @Transactional
    public AdminCategoryDto createCategory(CategoryRequest req) {
        try {
            long id = jdbc.sql("""
                    INSERT INTO service_categories (slug, name, description, icon, image_url, sort_order, is_active)
                    VALUES (:slug, :name, :description, :icon, :image, :sort, :active) RETURNING id
                    """)
                .params(categoryParams(req)).query(Long.class).single();
            return category(id);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("SLUG_TAKEN", "Another category already uses this slug");
        }
    }

    @Transactional
    public AdminCategoryDto updateCategory(long id, CategoryRequest req) {
        try {
            int rows = jdbc.sql("""
                    UPDATE service_categories SET slug = :slug, name = :name, description = :description, icon = :icon,
                           image_url = :image, sort_order = :sort, is_active = :active
                     WHERE id = :id
                    """)
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

    private static final String SERVICE_SELECT = """
        SELECT s.id, s.category_id, c.name AS category_name, s.slug, s.name, s.description, s.base_price,
               s.duration_minutes, s.is_active AS active,
               (SELECT count(*) FROM genie_services gs WHERE gs.service_id = s.id AND gs.is_active) AS genie_count
          FROM services s JOIN service_categories c ON c.id = s.category_id
        """;

    @Transactional(readOnly = true)
    public List<AdminServiceDto> services(Long categoryId) {
        return jdbc.sql(SERVICE_SELECT + " WHERE (CAST(:c AS bigint) IS NULL OR s.category_id = :c) ORDER BY c.sort_order, s.base_price")
            .param("c", categoryId).query(AdminServiceDto.class).list();
    }

    @Transactional
    public AdminServiceDto createService(ServiceRequest req) {
        requireCategory(req.categoryId());
        try {
            long id = jdbc.sql("""
                    INSERT INTO services (category_id, slug, name, description, base_price, duration_minutes, is_active)
                    VALUES (:category, :slug, :name, :description, :price, :duration, :active) RETURNING id
                    """)
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
            int rows = jdbc.sql("""
                    UPDATE services SET category_id = :category, slug = :slug, name = :name, description = :description,
                           base_price = :price, duration_minutes = :duration, is_active = :active
                     WHERE id = :id
                    """)
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
        return jdbc.sql(SERVICE_SELECT + " WHERE s.id = :id").param("id", id).query(AdminServiceDto.class).optional()
            .orElseThrow(() -> ApiException.notFound("SERVICE_NOT_FOUND", "Service not found"));
    }

    private void requireCategory(long categoryId) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM service_categories WHERE id = :id)")
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
        return jdbc.sql("""
                SELECT c.id AS city_id, c.name, c.state, c.is_active AS active, p.commission_rate, p.travel_free_km,
                       p.travel_base_fee, p.travel_per_km, p.travel_fee_cap, p.currency
                  FROM cities c LEFT JOIN city_pricing p ON p.city_id = c.id ORDER BY c.name
                """)
            .query(CityPricingDto.class).list();
    }

    @Transactional
    public CityPricingDto updatePricing(long cityId, PricingRequest req) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM cities WHERE id = :id)").param("id", cityId)
            .query(Boolean.class).single();
        if (!exists) {
            throw ApiException.notFound("CITY_NOT_FOUND", "City not found");
        }
        jdbc.sql("""
                INSERT INTO city_pricing (city_id, commission_rate, travel_free_km, travel_base_fee, travel_per_km,
                                          travel_fee_cap, updated_at)
                VALUES (:id, :rate, :free, :base, :perKm, :cap, now())
                ON CONFLICT (city_id) DO UPDATE SET commission_rate = EXCLUDED.commission_rate,
                    travel_free_km = EXCLUDED.travel_free_km, travel_base_fee = EXCLUDED.travel_base_fee,
                    travel_per_km = EXCLUDED.travel_per_km, travel_fee_cap = EXCLUDED.travel_fee_cap, updated_at = now()
                """)
            .param("id", cityId).param("rate", req.commissionRate()).param("free", req.travelFreeKm())
            .param("base", req.travelBaseFee()).param("perKm", req.travelPerKm()).param("cap", req.travelFeeCap())
            .update();
        return cityPricing().stream().filter(c -> c.cityId() == cityId).findFirst().orElseThrow();
    }
}
