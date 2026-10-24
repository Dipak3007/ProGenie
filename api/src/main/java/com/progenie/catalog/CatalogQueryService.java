package com.progenie.catalog;

import java.util.List;

import com.progenie.catalog.CatalogDtos.CategoryDetailDto;
import com.progenie.catalog.CatalogDtos.CategoryDto;
import com.progenie.catalog.CatalogDtos.CityDto;
import com.progenie.catalog.CatalogDtos.SearchHitDto;
import com.progenie.catalog.CatalogDtos.ServiceDto;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Read-only catalog queries with plain SQL (JdbcClient). Records are mapped by column name
 * (snake_case → camelCase). A Redis cache will sit in front of these in Phase 1.
 */
@Service
public class CatalogQueryService {

    private final JdbcClient jdbc;

    public CatalogQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<CityDto> cities() {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_ACTIVE_CITIES))
            .query(CityDto.class)
            .list();
    }

    public List<CategoryDto> categories() {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_CATEGORIES))
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
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_SEARCH_SERVICES))
            .param("q", q)
            .param("like", like)
            .param("limit", Math.clamp(limit, 1, 20))
            .query(SearchHitDto.class)
            .list();
    }

    public CategoryDetailDto category(String slug) {
        CategoryDto category = jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_CATEGORY_BY_SLUG))
            .param("slug", slug)
            .query(CategoryDto.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("CATEGORY_NOT_FOUND", "No such category: " + slug));
        List<ServiceDto> services = jdbc.sql(QueryUtil.get(QueryConstants.GET_CATALOG_CATEGORY_SERVICES))
            .param("categoryId", category.id())
            .query(ServiceDto.class)
            .list();
        return new CategoryDetailDto(category, services);
    }
}
