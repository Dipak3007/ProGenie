package com.progenie.catalog;

import java.math.BigDecimal;
import java.util.List;

/** Read models returned by the catalog endpoints. */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CityDto(long id, String name, String state) {
    }

    public record CategoryDto(long id, String slug, String name, String description, String icon,
                              String imageUrl, int serviceCount, BigDecimal startingPrice) {
    }

    public record ServiceDto(long id, String slug, String name, String description,
                             BigDecimal basePrice, int durationMinutes) {
    }

    public record CategoryDetailDto(CategoryDto category, List<ServiceDto> services) {
    }

    /** One search hit: a service and the category it belongs to. */
    public record SearchHitDto(long serviceId, String serviceSlug, String serviceName, String categorySlug,
                               String categoryName, BigDecimal basePrice, int durationMinutes) {
    }
}
