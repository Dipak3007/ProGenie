package com.progenie.provider;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.progenie.shared.error.ApiException;
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
        Row row = jdbc.sql("""
                SELECT gp.user_id AS genie_id, u.full_name AS genie_name, gp.verification_status, u.status AS user_status,
                       s.id AS service_id, s.name AS service_name, s.duration_minutes,
                       coalesce(gs.price_override, s.base_price) AS price, gp.service_radius_km
                  FROM genie_profiles gp
                  JOIN users u ON u.id = gp.user_id
                  LEFT JOIN genie_services gs ON gs.genie_id = gp.user_id AND gs.service_id = :serviceId AND gs.is_active
                  LEFT JOIN services s        ON s.id = gs.service_id AND s.is_active
                 WHERE gp.user_id = :genieId
                """)
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
    public boolean servesLocation(UUID genieId, double lat, double lng) {
        return jdbc.sql("""
                SELECT coalesce(ST_DWithin(gp.base_location, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                                           gp.service_radius_km * 1000), FALSE)
                  FROM genie_profiles gp WHERE gp.user_id = :id
                """)
            .param("lng", lng).param("lat", lat).param("id", genieId)
            .query(Boolean.class)
            .optional()
            .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<Window> weeklyAvailability(UUID genieId) {
        return jdbc.sql("SELECT day_of_week, start_time, end_time FROM genie_availability WHERE genie_id = :id")
            .param("id", genieId)
            .query(Window.class)
            .list();
    }

    @Transactional(readOnly = true)
    public List<Interval> timeOff(UUID genieId, Instant from, Instant to) {
        record Row(OffsetDateTime startsAt, OffsetDateTime endsAt) {
        }
        return jdbc.sql("""
                SELECT starts_at, ends_at FROM genie_time_off
                 WHERE genie_id = :id AND starts_at < :to AND ends_at > :from
                """)
            .param("id", genieId).param("from", Times.odt(from)).param("to", Times.odt(to))
            .query(Row.class)
            .list().stream()
            .map(r -> new Interval(r.startsAt().toInstant(), r.endsAt().toInstant()))
            .toList();
    }
}
