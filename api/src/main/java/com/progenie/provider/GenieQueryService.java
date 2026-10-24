package com.progenie.provider;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.progenie.provider.GenieDtos.GenieCardDto;
import com.progenie.provider.GenieDtos.GenieDetailDto;
import com.progenie.provider.GenieDtos.GenieServiceDto;
import com.progenie.provider.GenieDtos.ReviewDto;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Public Genie listing and profile. Only APPROVED Genies with an ACTIVE account are ever shown. */
@Service
public class GenieQueryService {

    /** Sort options exposed to the UI, mapped to safe ORDER BY clauses (never concatenate user input). */
    public enum Sort {
        RATING(QueryConstants.FRAGMENT_GENIE_ORDER_RATING),
        PRICE(QueryConstants.FRAGMENT_GENIE_ORDER_PRICE),
        EXPERIENCE(QueryConstants.FRAGMENT_GENIE_ORDER_EXPERIENCE);

        /** Key of the ORDER BY fragment in sql_scripts.properties. */
        private final String orderBy;

        Sort(String orderBy) {
            this.orderBy = orderBy;
        }
    }

    private final JdbcClient jdbc;

    public GenieQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<GenieCardDto> list(String categorySlug, Long serviceId, Sort sort, int limit) {
        JdbcClient.StatementSpec statement = jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_LIST, Map.of(
            "categoryFilter", categorySlug != null ? QueryConstants.FRAGMENT_GENIE_FILTER_CATEGORY : QueryConstants.FRAGMENT_NONE,
            "serviceFilter", serviceId != null ? QueryConstants.FRAGMENT_GENIE_FILTER_SERVICE : QueryConstants.FRAGMENT_NONE,
            "orderBy", sort.orderBy)))
            .param("limit", Math.clamp(limit, 1, 50));
        if (categorySlug != null) {
            statement = statement.param("category", categorySlug);
        }
        if (serviceId != null) {
            statement = statement.param("serviceId", serviceId);
        }
        return statement.query(GenieCardDto.class).list();
    }

    /** Cards for the given Genies (e.g. a customer's favourites), in the given order; unlisted Genies are skipped. */
    public List<GenieCardDto> cards(List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        List<GenieCardDto> found = jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_CARDS))
            .param("ids", ids)
            .query(GenieCardDto.class)
            .list();
        return ids.stream()
            .flatMap(id -> found.stream().filter(card -> card.id().equals(id)))
            .toList();
    }

    /** True if the Genie is approved, active and visible to customers. */
    public boolean isListed(UUID genieId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_GENIE_LISTED))
            .param("id", genieId)
            .query(Boolean.class)
            .single();
    }

    public GenieDetailDto detail(UUID genieId) {
        GenieCardDto card = jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_CARD))
            .param("id", genieId)
            .query(GenieCardDto.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("GENIE_NOT_FOUND", "Genie not found"));

        List<GenieServiceDto> services = jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_DETAIL_SERVICES))
            .param("id", genieId)
            .query(GenieServiceDto.class)
            .list();

        List<ReviewDto> reviews = jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_DETAIL_REVIEWS))
            .param("id", genieId)
            .query(ReviewDto.class)
            .list();

        return new GenieDetailDto(card, services, reviews);
    }
}
