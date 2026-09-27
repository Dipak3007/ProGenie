package com.progenie.catalog;

import java.util.List;

import com.progenie.catalog.CatalogDtos.CategoryDetailDto;
import com.progenie.catalog.CatalogDtos.CategoryDto;
import com.progenie.catalog.CatalogDtos.CityDto;
import com.progenie.catalog.CatalogDtos.SearchHitDto;
import com.progenie.catalog.CatalogDtos.ServiceDto;
import com.progenie.shared.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Read-only catalog queries with plain SQL (JdbcClient). Records are mapped by column name
 * (snake_case → camelCase). A Redis cache will sit in front of these in Phase 1.
 */
@Service
public class CatalogQueryService {

    private static final String CATEGORY_SELECT = """
        SELECT c.id, c.slug, c.name, c.description, c.icon, c.image_url,
               count(s.id)       AS service_count,
               min(s.base_price) AS starting_price
          FROM service_categories c
          LEFT JOIN services s ON s.category_id = c.id AND s.is_active
         WHERE c.is_active
        """;

    private final JdbcClient jdbc;

    public CatalogQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CityDto> cities() {
        return jdbc.sql("SELECT id, name, state FROM cities WHERE is_active ORDER BY name")
            .query(CityDto.class)
            .list();
    }

    public List<CategoryDto> categories() {
        return jdbc.sql(CATEGORY_SELECT + " GROUP BY c.id ORDER BY c.sort_order")
            .query(CategoryDto.class)
            .list();
    }

    /**
     * Full-text search over service names/descriptions (GIN index on services.search_vector),
     * plus a substring match so partial words like "plumb" or "ac" also work.
     */
    public List<SearchHitDto> search(String query, int limit) {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) {
            return List.of();
        }
        String like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("""
                SELECT s.id AS service_id, s.slug AS service_slug, s.name AS service_name,
                       c.slug AS category_slug, c.name AS category_name, s.base_price, s.duration_minutes
                  FROM services s
                  JOIN service_categories c ON c.id = s.category_id AND c.is_active
                 WHERE s.is_active
                   AND (s.search_vector @@ plainto_tsquery('simple', :q)
                        OR s.name ILIKE :like OR c.name ILIKE :like OR s.description ILIKE :like)
                 ORDER BY ts_rank(s.search_vector, plainto_tsquery('simple', :q)) DESC,
                          (s.name ILIKE :like) DESC, c.sort_order, s.base_price
                 LIMIT :limit
                """)
            .param("q", q)
            .param("like", like)
            .param("limit", Math.clamp(limit, 1, 20))
            .query(SearchHitDto.class)
            .list();
    }

    public CategoryDetailDto category(String slug) {
        CategoryDto category = jdbc.sql(CATEGORY_SELECT + " AND c.slug = :slug GROUP BY c.id")
            .param("slug", slug)
            .query(CategoryDto.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "No such category: " + slug));
        List<ServiceDto> services = jdbc.sql("""
                SELECT s.id, s.slug, s.name, s.description, s.base_price, s.duration_minutes
                  FROM services s
                 WHERE s.category_id = :categoryId AND s.is_active
                 ORDER BY s.base_price
                """)
            .param("categoryId", category.id())
            .query(ServiceDto.class)
            .list();
        return new CategoryDetailDto(category, services);
    }
}
