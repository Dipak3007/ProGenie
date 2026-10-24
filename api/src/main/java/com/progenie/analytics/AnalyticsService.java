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
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
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
        jdbc.sql(QueryUtil.get(QueryConstants.GET_ANALYTICS_BOOKINGS_BY_STATUS))
            .param("from", r.fromTs()).param("to", r.toTs())
            .query(StatusCount.class).list()
            .forEach(s -> byStatus.put(s.status(), s.count()));
        long created = byStatus.values().stream().mapToLong(Long::longValue).sum();

        record Money(long completed, BigDecimal gmv, BigDecimal commission) {
        }
        Money money = jdbc.sql(QueryUtil.get(QueryConstants.GET_ANALYTICS_MONEY_TOTALS))
            .param("from", r.fromTs()).param("to", r.toTs()).query(Money.class).single();

        record Response(long responded, long accepted) {
        }
        Response response = jdbc.sql(QueryUtil.get(QueryConstants.GET_ANALYTICS_RESPONSE_STATS))
            .param("from", r.fromTs()).param("to", r.toTs()).query(Response.class).single();

        record People(long newCustomers, long newGenies, long approvedGenies, long pendingVerifications,
                      long flaggedGenies, long outstandingFees) {
        }
        People people = jdbc.sql(QueryUtil.get(QueryConstants.GET_ANALYTICS_PEOPLE_TOTALS))
            .param("from", r.fromTs()).param("to", r.toTs()).query(People.class).single();

        List<CategoryStat> categories = jdbc.sql(QueryUtil.get(QueryConstants.GET_ANALYTICS_TOP_CATEGORIES))
            .param("from", r.fromTs()).param("to", r.toTs()).query(CategoryStat.class).list();

        List<GenieStat> genies = jdbc.sql(QueryUtil.get(QueryConstants.GET_ANALYTICS_TOP_GENIES))
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
        Counts c = jdbc.sql(QueryUtil.get(QueryConstants.GET_ANALYTICS_GENIE_JOB_COUNTS))
            .param("g", genieId).param("from", r.fromTs()).param("to", r.toTs()).query(Counts.class).single();
        BigDecimal earnings = jdbc.sql(QueryUtil.get(QueryConstants.GET_ANALYTICS_GENIE_EARNINGS))
            .param("g", genieId).param("from", r.fromTs()).param("to", r.toTs()).query(BigDecimal.class).single();
        record Rating(BigDecimal avgRating, int ratingCount) {
        }
        Rating rating = jdbc.sql(QueryUtil.get(QueryConstants.GET_ANALYTICS_GENIE_RATING))
            .param("g", genieId).query(Rating.class).optional()
            .orElseThrow(() -> ApiException.notFound("GENIE_PROFILE_NOT_FOUND", "Genie profile not found"));
        return new GenieStatsDto(r.from(), r.to(), c.requests(), c.completed(), c.cancelled(),
            percent(c.accepted(), c.responded()), earnings, rating.avgRating(), rating.ratingCount(), daily(r, genieId));
    }

    private List<DayStat> daily(Range r, UUID genieId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_ANALYTICS_DAILY_SERIES))
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
