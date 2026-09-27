package com.progenie.provider;

import java.util.List;
import java.util.UUID;

import com.progenie.provider.GenieDtos.GenieCardDto;
import com.progenie.provider.GenieDtos.GenieDetailDto;
import com.progenie.provider.GenieDtos.GenieServiceDto;
import com.progenie.provider.GenieDtos.ReviewDto;
import com.progenie.shared.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Public Genie listing and profile. Only APPROVED Genies with an ACTIVE account are ever shown. */
@Service
public class GenieQueryService {

    /** Sort options exposed to the UI, mapped to safe ORDER BY clauses (never concatenate user input). */
    public enum Sort {
        RATING("avg_rating DESC, rating_count DESC"),
        PRICE("starting_price ASC"),
        EXPERIENCE("experience_years DESC");

        private final String orderBy;

        Sort(String orderBy) {
            this.orderBy = orderBy;
        }
    }

    private static final String CARD_SELECT = """
        SELECT u.id, u.full_name, gp.bio, gp.experience_years, gp.avg_rating, gp.rating_count,
               gp.completed_jobs, gp.base_area, gp.is_online AS online,
               min(coalesce(gs.price_override, s.base_price)) AS starting_price,
               string_agg(DISTINCT c.name, ', ')              AS categories,
               min(c.slug)                                    AS category_slug,
               min(c.image_url)                               AS cover_image
          FROM genie_profiles gp
          JOIN users u               ON u.id = gp.user_id AND u.status = 'ACTIVE'
          JOIN genie_services gs     ON gs.genie_id = gp.user_id AND gs.is_active
          JOIN services s            ON s.id = gs.service_id AND s.is_active
          JOIN service_categories c  ON c.id = s.category_id
         WHERE gp.verification_status = 'APPROVED'
        """;

    private static final String CARD_GROUP_BY =
        " GROUP BY u.id, u.full_name, gp.bio, gp.experience_years, gp.avg_rating, gp.rating_count,"
            + " gp.completed_jobs, gp.base_area, gp.is_online";

    private final JdbcClient jdbc;

    public GenieQueryService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<GenieCardDto> list(String categorySlug, Long serviceId, Sort sort, int limit) {
        StringBuilder sql = new StringBuilder(CARD_SELECT);
        if (categorySlug != null) {
            sql.append(" AND c.slug = :category");
        }
        if (serviceId != null) {
            sql.append(" AND s.id = :serviceId");
        }
        sql.append(CARD_GROUP_BY).append(" ORDER BY ").append(sort.orderBy).append(" LIMIT :limit");

        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString()).param("limit", Math.clamp(limit, 1, 50));
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
        List<GenieCardDto> found = jdbc.sql(CARD_SELECT + " AND gp.user_id IN (:ids)" + CARD_GROUP_BY)
            .param("ids", ids)
            .query(GenieCardDto.class)
            .list();
        return ids.stream()
            .flatMap(id -> found.stream().filter(card -> card.id().equals(id)))
            .toList();
    }

    /** True if the Genie is approved, active and visible to customers. */
    public boolean isListed(UUID genieId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM genie_profiles gp JOIN users u ON u.id = gp.user_id
                                WHERE gp.user_id = :id AND gp.verification_status = 'APPROVED' AND u.status = 'ACTIVE')
                """)
            .param("id", genieId)
            .query(Boolean.class)
            .single();
    }

    public GenieDetailDto detail(UUID genieId) {
        GenieCardDto card = jdbc.sql(CARD_SELECT + " AND gp.user_id = :id" + CARD_GROUP_BY)
            .param("id", genieId)
            .query(GenieCardDto.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("GENIE_NOT_FOUND", "Genie not found"));

        List<GenieServiceDto> services = jdbc.sql("""
                SELECT s.id AS service_id, s.name, coalesce(gs.price_override, s.base_price) AS price, s.duration_minutes
                  FROM genie_services gs
                  JOIN services s ON s.id = gs.service_id
                 WHERE gs.genie_id = :id AND gs.is_active AND s.is_active
                 ORDER BY price
                """)
            .param("id", genieId)
            .query(GenieServiceDto.class)
            .list();

        List<ReviewDto> reviews = jdbc.sql("""
                SELECT r.rating, r.comment, split_part(u.full_name, ' ', 1) AS customer_name, r.created_at, r.genie_reply
                  FROM reviews r
                  JOIN users u ON u.id = r.customer_id
                 WHERE r.genie_id = :id
                 ORDER BY r.created_at DESC
                 LIMIT 10
                """)
            .param("id", genieId)
            .query(ReviewDto.class)
            .list();

        return new GenieDetailDto(card, services, reviews);
    }
}
