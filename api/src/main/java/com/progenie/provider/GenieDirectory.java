package com.progenie.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The provider module's public, read-only API for other modules (booking, pricing):
 * "can this Genie take this job?", weekly availability and time off.
 */
@Service
public class GenieDirectory {

    /** A service a Genie offers, as needed to create a booking. */
    public record Offer(UUID genieId, String genieName, long serviceId, String serviceName, int durationMinutes,
                        BigDecimal price, int serviceRadiusKm) {
    }

    public record Window(int dayOfWeek, LocalTime startTime, LocalTime endTime) {
    }

    public record Interval(Instant start, Instant end) {
    }

    private final JdbcClient jdbc;

    public GenieDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The offer if the Genie is approved and active and offers the (active) service; otherwise a 404/422. */
    @Transactional(readOnly = true)
    public Offer bookableOffer(UUID genieId, long serviceId) {
        record Row(UUID genieId, String genieName, String verificationStatus, String userStatus, Long serviceId,
                   String serviceName, Integer durationMinutes, BigDecimal price, int serviceRadiusKm) {
        }
        Row row = jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_BOOKABLE_OFFER))
            .param("genieId", genieId)
            .param("serviceId", serviceId)
            .query(Row.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("GENIE_NOT_FOUND", "Genie not found"));
        if (!"APPROVED".equals(row.verificationStatus()) || !"ACTIVE".equals(row.userStatus())) {
            throw ApiException.unprocessable("GENIE_UNAVAILABLE", "This Genie is not taking bookings right now");
        }
        if (row.serviceId() == null) {
            throw ApiException.unprocessable("SERVICE_NOT_OFFERED", "This Genie does not offer that service");
        }
        return new Offer(row.genieId(), row.genieName(), row.serviceId(), row.serviceName(), row.durationMinutes(),
            row.price(), row.serviceRadiusKm());
    }

    /** True if the point is inside the Genie's service radius (straight-line, from their base location). */
    @Transactional(readOnly = true)
    /** True if the Genie works in the given city (their service area). */
    public boolean servesCity(UUID genieId, long cityId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_GENIE_SERVES_CITY))
            .param("id", genieId)
            .param("cityId", cityId)
            .query(Boolean.class)
            .single();
    }

    public boolean servesLocation(UUID genieId, double lat, double lng) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_SERVES_LOCATION))
            .param("lng", lng).param("lat", lat).param("id", genieId)
            .query(Boolean.class)
            .optional()
            .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<Window> weeklyAvailability(UUID genieId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_WEEKLY_AVAILABILITY))
            .param("id", genieId)
            .query(Window.class)
            .list();
    }

    @Transactional(readOnly = true)
    public List<Interval> timeOff(UUID genieId, Instant from, Instant to) {
        record Row(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        }
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_TIME_OFF_BETWEEN))
            .param("id", genieId).param("from", Times.odt(from)).param("to", Times.odt(to))
            .query(Row.class)
            .list().stream()
            .map(r -> new Interval(r.startsAt().toInstant(), r.endsAt().toInstant()))
            .toList();
    }
}
