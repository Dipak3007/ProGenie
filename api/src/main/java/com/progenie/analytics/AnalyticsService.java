package com.progenie.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.progenie.shared.config.AppProperties;
import com.progenie.shared.error.ApiException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only business metrics straight from PostgreSQL. Fine at trial scale; the same queries can
 * move to a read replica or a nightly rollup table when volumes grow.
 */
@Service
public class AnalyticsService {

    static final int MAX_RANGE_DAYS = 366;

    public record DayStat(LocalDate day, long bookings, long completed, BigDecimal gmv) {
    }

    public record CategoryStat(String slug, String name, long completed, BigDecimal gmv) {
    }

    public record GenieStat(UUID id, String name, long completed, BigDecimal avgRating, BigDecimal earnings) {
    }

    public record OverviewDto(LocalDate from, LocalDate to, long bookingsCreated, Map<String, Long> bookingsByStatus,
                              long completed, BigDecimal gmv, BigDecimal commission, BigDecimal avgOrderValue,
                              BigDecimal acceptanceRate, BigDecimal cancellationRate, long newCustomers, long newGenies,
                              long approvedGenies, long pendingVerifications, long flaggedGenies,
                              long outstandingFees, List<CategoryStat> topCategories, List<GenieStat> topGenies,
                              List<DayStat> daily) {
    }

    public record GenieStatsDto(LocalDate from, LocalDate to, long requestsReceived, long completed,
                                long cancelledByGenie, BigDecimal acceptanceRate, BigDecimal earnings,
                                BigDecimal avgRating, int ratingCount, List<DayStat> daily) {
    }

    private final JdbcClient jdbc;
    private final ZoneId zone;
    private final Clock clock;

    public AnalyticsService(JdbcClient jdbc, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.zone = ZoneId.of(props.timezone());
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OverviewDto overview(LocalDate from, LocalDate to) {
        Range r = range(from, to, 30);
        Map<String, Long> byStatus = new LinkedHashMap<>();
        record StatusCount(String status, long count) {
        }
        jdbc.sql("""
                SELECT status, count(*) AS count FROM bookings
                 WHERE created_at >= :from AND created_at < :to GROUP BY status ORDER BY count(*) DESC
                """)
            .param("from", r.fromTs()).param("to", r.toTs())
            .query(StatusCount.class).list()
            .forEach(s -> byStatus.put(s.status(), s.count()));
        long created = byStatus.values().stream().mapToLong(Long::longValue).sum();

        record Money(long completed, BigDecimal gmv, BigDecimal commission) {
        }
        Money money = jdbc.sql("""
                SELECT count(*) AS completed, coalesce(sum(total_amount), 0) AS gmv,
                       coalesce(sum(commission_amount), 0) AS commission
                  FROM bookings WHERE status = 'COMPLETED' AND completed_at >= :from AND completed_at < :to
                """)
            .param("from", r.fromTs()).param("to", r.toTs()).query(Money.class).single();

        record Response(long responded, long accepted) {
        }
        Response response = jdbc.sql("""
                SELECT count(*) FILTER (WHERE accepted_at IS NOT NULL OR status IN ('REJECTED', 'EXPIRED')) AS responded,
                       count(*) FILTER (WHERE accepted_at IS NOT NULL) AS accepted
                  FROM bookings WHERE created_at >= :from AND created_at < :to
                """)
            .param("from", r.fromTs()).param("to", r.toTs()).query(Response.class).single();

        record People(long newCustomers, long newGenies, long approvedGenies, long pendingVerifications,
                      long flaggedGenies, long outstandingFees) {
        }
        People people = jdbc.sql("""
                SELECT (SELECT count(*) FROM users WHERE role = 'CUSTOMER' AND created_at >= :from AND created_at < :to) AS new_customers,
                       (SELECT count(*) FROM users WHERE role = 'GENIE' AND created_at >= :from AND created_at < :to) AS new_genies,
                       (SELECT count(*) FROM genie_profiles WHERE verification_status = 'APPROVED') AS approved_genies,
                       (SELECT count(*) FROM genie_profiles WHERE verification_status = 'UNDER_REVIEW') AS pending_verifications,
                       (SELECT count(*) FROM genie_profiles WHERE flagged_at IS NOT NULL) AS flagged_genies,
                       (SELECT count(*) FROM bookings WHERE cancellation_fee_status = 'DUE') AS outstanding_fees
                """)
            .param("from", r.fromTs()).param("to", r.toTs()).query(People.class).single();

        List<CategoryStat> categories = jdbc.sql("""
                SELECT c.slug, c.name, count(*) AS completed, sum(b.total_amount) AS gmv
                  FROM bookings b JOIN services s ON s.id = b.service_id JOIN service_categories c ON c.id = s.category_id
                 WHERE b.status = 'COMPLETED' AND b.completed_at >= :from AND b.completed_at < :to
                 GROUP BY c.slug, c.name ORDER BY count(*) DESC, sum(b.total_amount) DESC LIMIT 5
                """)
            .param("from", r.fromTs()).param("to", r.toTs()).query(CategoryStat.class).list();

        List<GenieStat> genies = jdbc.sql("""
                SELECT u.id, u.full_name AS name, count(*) AS completed, gp.avg_rating, sum(b.genie_payout) AS earnings
                  FROM bookings b JOIN users u ON u.id = b.genie_id JOIN genie_profiles gp ON gp.user_id = b.genie_id
                 WHERE b.status = 'COMPLETED' AND b.completed_at >= :from AND b.completed_at < :to
                 GROUP BY u.id, u.full_name, gp.avg_rating ORDER BY count(*) DESC, gp.avg_rating DESC LIMIT 5
                """)
            .param("from", r.fromTs()).param("to", r.toTs()).query(GenieStat.class).list();

        return new OverviewDto(r.from(), r.to(), created, byStatus, money.completed(), money.gmv(), money.commission(),
            ratio(money.gmv(), money.completed()), percent(response.accepted(), response.responded()),
            percent(byStatus.getOrDefault("CANCELLED", 0L), created), people.newCustomers(), people.newGenies(),
            people.approvedGenies(), people.pendingVerifications(), people.flaggedGenies(), people.outstandingFees(),
            categories, genies, daily(r, null));
    }

    @Transactional(readOnly = true)
    public GenieStatsDto genieStats(UUID genieId, int days) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Range r = range(today.minusDays(Math.clamp(days, 1, MAX_RANGE_DAYS) - 1L), today, 30);
        record Counts(long requests, long completed, long cancelled, long responded, long accepted) {
        }
        Counts c = jdbc.sql("""
                SELECT count(*) AS requests,
                       count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
                       count(*) FILTER (WHERE status = 'CANCELLED' AND cancelled_by = :g) AS cancelled,
                       count(*) FILTER (WHERE accepted_at IS NOT NULL OR status IN ('REJECTED', 'EXPIRED')) AS responded,
                       count(*) FILTER (WHERE accepted_at IS NOT NULL) AS accepted
                  FROM bookings WHERE genie_id = :g AND created_at >= :from AND created_at < :to
                """)
            .param("g", genieId).param("from", r.fromTs()).param("to", r.toTs()).query(Counts.class).single();
        BigDecimal earnings = jdbc.sql("""
                SELECT coalesce(sum(amount), 0) FROM ledger_entries
                 WHERE genie_id = :g AND direction = 'C' AND account IN ('GENIE_EARNINGS', 'TIPS')
                   AND created_at >= :from AND created_at < :to
                """)
            .param("g", genieId).param("from", r.fromTs()).param("to", r.toTs()).query(BigDecimal.class).single();
        record Rating(BigDecimal avgRating, int ratingCount) {
        }
        Rating rating = jdbc.sql("SELECT avg_rating, rating_count FROM genie_profiles WHERE user_id = :g")
            .param("g", genieId).query(Rating.class).optional()
            .orElseThrow(() -> ApiException.notFound("GENIE_PROFILE_NOT_FOUND", "Genie profile not found"));
        return new GenieStatsDto(r.from(), r.to(), c.requests(), c.completed(), c.cancelled(),
            percent(c.accepted(), c.responded()), earnings, rating.avgRating(), rating.ratingCount(), daily(r, genieId));
    }

    private List<DayStat> daily(Range r, UUID genieId) {
        return jdbc.sql("""
                WITH days AS (
                    SELECT d::date AS day FROM generate_series(CAST(:fromDay AS date), CAST(:toDay AS date), interval '1 day') d
                )
                SELECT days.day,
                       (SELECT count(*) FROM bookings b
                         WHERE (CAST(:g AS uuid) IS NULL OR b.genie_id = :g)
                           AND (b.created_at AT TIME ZONE :tz)::date = days.day) AS bookings,
                       (SELECT count(*) FROM bookings b
                         WHERE (CAST(:g AS uuid) IS NULL OR b.genie_id = :g) AND b.status = 'COMPLETED'
                           AND (b.completed_at AT TIME ZONE :tz)::date = days.day) AS completed,
                       (SELECT coalesce(sum(b.total_amount), 0) FROM bookings b
                         WHERE (CAST(:g AS uuid) IS NULL OR b.genie_id = :g) AND b.status = 'COMPLETED'
                           AND (b.completed_at AT TIME ZONE :tz)::date = days.day) AS gmv
                  FROM days ORDER BY days.day
                """)
            .param("fromDay", r.from()).param("toDay", r.to()).param("g", genieId).param("tz", zone.getId())
            .query(DayStat.class).list();
    }

    /** Inclusive local dates → [fromTs, toTs) timestamps in the business time zone. */
    private record Range(LocalDate from, LocalDate to, OffsetDateTime fromTs, OffsetDateTime toTs) {
    }

    private Range range(LocalDate from, LocalDate to, int defaultDays) {
        LocalDate end = to == null ? LocalDate.now(clock.withZone(zone)) : to;
        LocalDate start = from == null ? end.minusDays(defaultDays - 1L) : from;
        if (start.isAfter(end)) {
            throw ApiException.badRequest("INVALID_RANGE", "'from' must be on or before 'to'");
        }
        if (start.plusDays(MAX_RANGE_DAYS).isBefore(end)) {
            throw ApiException.badRequest("INVALID_RANGE", "The range can be at most one year");
        }
        return new Range(start, end, start.atStartOfDay(zone).toOffsetDateTime(),
            end.plusDays(1).atStartOfDay(zone).toOffsetDateTime());
    }

    private static BigDecimal percent(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 100.0 / whole).setScale(1, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(BigDecimal amount, long count) {
        return count == 0 ? BigDecimal.ZERO : amount.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }
}
