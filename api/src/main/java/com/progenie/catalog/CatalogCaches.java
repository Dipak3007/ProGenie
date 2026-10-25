package com.progenie.catalog;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.progenie.catalog.CatalogDtos.AreaDto;
import com.progenie.catalog.CatalogDtos.CategoryDetailDto;
import com.progenie.catalog.CatalogDtos.CategoryDto;
import com.progenie.catalog.CatalogDtos.CityDto;
import com.progenie.shared.cache.CacheSpec;
import com.progenie.shared.cache.ReadCacheProperties;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The catalog's read caches (Phase 2B-6). Public, read on every page, and changed only by admins: every admin
 * write in {@link CatalogAdminService} evicts them all and publishes {@link CatalogChanged} for other modules.
 */
@Configuration
public class CatalogCaches {

    public static final String CITIES = "catalog-cities";
    public static final String CITY = "catalog-city";
    public static final String AREAS = "catalog-areas";
    public static final String CATEGORIES = "catalog-categories";
    public static final String CATEGORY = "catalog-category";

    /** Something in the catalog (city, PIN code, category, service, pricing) changed: published after the write. */
    public record CatalogChanged(String what) {
    }

    /** On an admin write: empties every catalog cache (after the commit; admin writes are rare). */
    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @CacheEvict(cacheNames = {CITIES, CITY, AREAS, CATEGORIES, CATEGORY}, allEntries = true)
    @interface EvictsCatalog {
    }

    @Bean
    CacheSpec catalogCitiesCache(ReadCacheProperties p) {
        return CacheSpec.listOf(CITIES, p.catalogTtl(), CityDto.class);
    }

    @Bean
    CacheSpec catalogCityCache(ReadCacheProperties p) {
        return CacheSpec.of(CITY, p.catalogTtl(), CityDto.class);
    }

    @Bean
    CacheSpec catalogAreasCache(ReadCacheProperties p) {
        return CacheSpec.listOf(AREAS, p.catalogTtl(), AreaDto.class);
    }

    @Bean
    CacheSpec catalogCategoriesCache(ReadCacheProperties p) {
        return CacheSpec.listOf(CATEGORIES, p.catalogTtl(), CategoryDto.class);
    }

    @Bean
    CacheSpec catalogCategoryCache(ReadCacheProperties p) {
        return CacheSpec.of(CATEGORY, p.catalogTtl(), CategoryDetailDto.class);
    }
}
