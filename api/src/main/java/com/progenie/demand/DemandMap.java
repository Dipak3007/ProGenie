package com.progenie.demand;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.progenie.shared.config.AppProperties;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.Times;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin demand heatmap (Phase 3D), grouped by PIN code (the centre we store for each served PIN code; points
 * without a PIN code are grouped in ~1 km squares). Two layers:
 * <ul>
 *   <li>BOOKINGS: bookings made (any outcome, free redos left out)</li>
 *   <li>UNMET: PIN codes and places we don't serve, pages with no Genie in the city, Genie Now checks with nobody
 *   free, and Genie Now searches nobody accepted, each with the reason when there is one</li>
 * </ul>
 * Filters: city, category, last 7/30/90 days, weekday and hour of day (India time, when the customer asked).
 * A PIN code we don't serve has no stored centre, so it is listed but not drawn ({@code lat} null).
 */
@Service
public class DemandMap {

    public enum Layer { BOOKINGS, UNMET }

    /** One place on the map. {@code kinds}: the unmet layer's count per kind (empty for bookings). */
    public record DemandCell(String key, @Nullable String pincode, @Nullable String area, @Nullable Double lat, @Nullable Double lng,
                       int count, Map<String, Integer> kinds) {
    }

    /**
     * The map for the filters. {@code unplaced}: counted but without a place (e.g. a page with no Genie seen by a
     * visitor who isn't signed in). {@code byHour} (0–23) and {@code byWeekday} (Monday first) ignore the weekday and
     * hour filters, so they show when demand happens.
     */
    public record DemandMapDto(String layer, int days, OffsetDateTime from, int total, int unplaced, List<DemandCell> cells,
                               Map<String, Integer> byKind, Map<String, Integer> byReason, int[] byHour, int[] byWeekday) {
    }

    record CellRow(String cellKey, @Nullable String pincode, @Nullable String area, @Nullable Double lat, @Nullable Double lng,
                   int count, int pincodeUnserved, int locationUnserved, int noGenieListed, int instantNoneFree,
                   int instantNoGenie) {
    }

    record SummaryRow(String kind, @Nullable String reason, int count, int unplaced) {
    }

    record TimeRow(int dow, int hr, int count) {
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final String timezone;

    public DemandMap(JdbcClient jdbc, Clock clock, AppProperties app) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.timezone = app.timezone();
    }

    @Transactional(readOnly = true)
    public DemandMapDto map(Layer layer, int days, @Nullable Long cityId, @Nullable Long categoryId, @Nullable Integer weekday,
                            @Nullable Integer hour) {
        if (days != 7 && days != 30 && days != 90) {
            throw ApiException.badRequest("INVALID_PERIOD", "Choose the last 7, 30 or 90 days");
        }
        if ((weekday != null && (weekday < 1 || weekday > 7)) || (hour != null && (hour < 0 || hour > 23))) {
            throw ApiException.badRequest("INVALID_FILTER", "Weekday is 1 (Monday) to 7, hour is 0 to 23");
        }
        OffsetDateTime from = Times.odt(clock.instant().minus(java.time.Duration.ofDays(days)));
        boolean unmet = layer == Layer.UNMET;

        List<DemandCell> cells = filtered(unmet ? QueryConstants.GET_DEMAND_UNMET_CELLS : QueryConstants.GET_DEMAND_BOOKING_CELLS,
            from, cityId, categoryId, weekday, hour)
            .query(CellRow.class).list().stream().map(r -> toCell(r, unmet)).toList();

        Map<String, Integer> byKind = new LinkedHashMap<>();
        Map<String, Integer> byReason = new LinkedHashMap<>();
        int total = 0;
        int unplaced = 0;
        for (SummaryRow r : filtered(unmet ? QueryConstants.GET_DEMAND_UNMET_SUMMARY : QueryConstants.GET_DEMAND_BOOKING_SUMMARY,
            from, cityId, categoryId, weekday, hour).query(SummaryRow.class).list()) {
            total += r.count();
            unplaced += r.unplaced();
            byKind.merge(r.kind(), r.count(), Integer::sum);
            if (r.reason() != null) {
                byReason.merge(r.reason(), r.count(), Integer::sum);
            }
        }

        int[] byHour = new int[24];
        int[] byWeekday = new int[7];
        for (TimeRow t : jdbc.sql(QueryUtil.get(unmet ? QueryConstants.GET_DEMAND_UNMET_TIMES : QueryConstants.GET_DEMAND_BOOKING_TIMES))
            .param("tz", timezone).param("from", from).param("cityId", cityId).param("categoryId", categoryId)
            .query(TimeRow.class).list()) {
            byHour[t.hr()] += t.count();
            byWeekday[t.dow() - 1] += t.count();
        }
        return new DemandMapDto(layer.name(), days, from, total, unplaced, cells, byKind, byReason, byHour, byWeekday);
    }

    private JdbcClient.StatementSpec filtered(String key, OffsetDateTime from, @Nullable Long cityId, @Nullable Long categoryId,
                                              @Nullable Integer weekday, @Nullable Integer hour) {
        return jdbc.sql(QueryUtil.get(key)).param("tz", timezone).param("from", from).param("cityId", cityId)
            .param("categoryId", categoryId).param("weekday", weekday).param("hour", hour);
    }

    private static DemandCell toCell(CellRow r, boolean unmet) {
        Map<String, Integer> kinds = new LinkedHashMap<>();
        if (unmet) {
            put(kinds, "PINCODE_UNSERVED", r.pincodeUnserved());
            put(kinds, "LOCATION_UNSERVED", r.locationUnserved());
            put(kinds, "NO_GENIE_LISTED", r.noGenieListed());
            put(kinds, "INSTANT_NONE_FREE", r.instantNoneFree());
            put(kinds, "INSTANT_NO_GENIE", r.instantNoGenie());
        }
        return new DemandCell(r.cellKey(), r.pincode(), r.area(), r.lat(), r.lng(), r.count(), kinds);
    }

    private static void put(Map<String, Integer> kinds, String kind, int n) {
        if (n > 0) {
            kinds.put(kind, n);
        }
    }
}
