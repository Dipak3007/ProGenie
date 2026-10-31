package com.progenie.booking;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.progenie.booking.BookingQueryService.Viewer;
import com.progenie.messaging.LiveHub;
import com.progenie.pricing.PricingService;
import com.progenie.pricing.PricingService.Trip;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.util.QueryUtil;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Live "on the way" tracking (Phase 3C-10). While a booking is ON_THE_WAY the Genie's page sends its position about
 * every 20 seconds; only the latest one is kept ({@code booking_tracking}, one row per booking, no history), with the
 * distance to the customer's address (OSRM road distance when configured, else straight line × 1.3) and an estimated
 * arrival time. Each update is pushed to the customer over the live stream ({@code tracking} event). The row is
 * deleted when the job starts or the booking is cancelled ({@link BookingSupport}), and a sweep removes any leftover.
 */
@Service
public class BookingTracking {

    private static final Logger log = LoggerFactory.getLogger(BookingTracking.class);

    /** Average speed for the arrival estimate in city traffic. */
    static final double CITY_SPEED_KMH = 22.0;

    /** A position from the Genie's phone (browser geolocation). */
    public record LocationRequest(@NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
                                  @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
                                  @DecimalMin("0") @DecimalMax("100000") Double accuracyM) {
    }

    /**
     * What the customer's map shows. {@code active} false (and no position) unless the booking is ON_THE_WAY;
     * {@code lat/lng} stay null until the first position arrives. {@code updatedAt} lets the page say
     * "last seen 3 min ago" when the Genie's page stops sending.
     */
    public record TrackingDto(UUID bookingId, String status, boolean active, @Nullable OffsetDateTime travelStartedAt,
                              @Nullable Double lat, @Nullable Double lng, @Nullable BigDecimal accuracyM,
                              @Nullable BigDecimal distanceKm, @Nullable String distanceMethod, @Nullable Integer etaMinutes,
                              @Nullable OffsetDateTime updatedAt, @Nullable Double customerLat, @Nullable Double customerLng) {
    }

    record Row(UUID bookingId, String status, UUID customerId, UUID genieId, OffsetDateTime travelStartedAt, Double lat,
               Double lng, BigDecimal accuracyM, BigDecimal distanceKm, String distanceMethod, Integer etaMinutes,
               OffsetDateTime updatedAt, Double customerLat, Double customerLng) {
    }

    private final JdbcClient jdbc;
    private final PricingService pricing;
    private final LiveHub live;
    private final JobLock lock;

    public BookingTracking(JdbcClient jdbc, PricingService pricing, LiveHub live, JobLock lock) {
        this.jdbc = jdbc;
        this.pricing = pricing;
        this.live = live;
        this.lock = lock;
    }

    /** The Genie's latest position for their booking that is on the way. 422 NOT_ON_THE_WAY otherwise. */
    @Transactional
    public TrackingDto update(UUID genieId, UUID bookingId, LocationRequest req) {
        Row b = row(bookingId).filter(r -> r.genieId().equals(genieId))
            .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking not found"));
        if (!"ON_THE_WAY".equals(b.status())) {
            throw ApiException.unprocessable("NOT_ON_THE_WAY", "Your location is only shared while you are on the way");
        }
        BigDecimal km = BigDecimal.ZERO;
        String method = "ESTIMATE";
        if (b.customerLat() != null && b.customerLng() != null) {
            Trip trip = pricing.distanceBetween(req.lat(), req.lng(), b.customerLat(), b.customerLng());
            km = trip.km();
            method = trip.method().name();
        }
        jdbc.sql(QueryUtil.get(QueryConstants.UPSERT_BOOKING_TRACKING))
            .param("id", bookingId).param("lat", req.lat()).param("lng", req.lng())
            .param("acc", req.accuracyM() == null ? null : BigDecimal.valueOf(req.accuracyM()).setScale(1, RoundingMode.HALF_UP))
            .param("km", km).param("method", method).param("eta", etaMinutes(km))
            .update();
        TrackingDto dto = toDto(row(bookingId).orElseThrow());
        UUID customer = b.customerId();
        live.afterCommit(() -> live.toUser(customer, "tracking", dto));
        log.debug("Booking {}: Genie position updated, {} km away", bookingId, km);
        return dto;
    }

    /** The map for the booking's customer, its Genie or an admin. */
    @Transactional(readOnly = true)
    public TrackingDto get(UUID bookingId, Viewer viewer, UUID viewerId) {
        Row r = row(bookingId)
            .filter(row -> viewer == Viewer.ADMIN
                || (viewer == Viewer.CUSTOMER && row.customerId().equals(viewerId))
                || (viewer == Viewer.GENIE && row.genieId().equals(viewerId)))
            .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking not found"));
        return toDto(r);
    }

    /** Safety net: no position outlives the trip, even if a status change skipped the clean-up. */
    @Scheduled(fixedDelayString = "${progenie.booking.tracking-sweep-interval:10m}", initialDelayString = "1m")
    @Transactional
    public void sweep() {
        if (!lock.tryAcquire("tracking-sweep")) {
            return;
        }
        int removed = jdbc.sql(QueryUtil.get(QueryConstants.DELETE_BOOKING_STALE_TRACKING)).update();
        if (removed > 0) {
            log.info("Tracking sweep: {} positions of bookings no longer on the way removed", removed);
        }
    }

    /** Minutes to arrive at {@link #CITY_SPEED_KMH}, rounded up; 0 when already there. */
    static int etaMinutes(BigDecimal km) {
        if (km.signum() <= 0) {
            return 0;
        }
        return (int) Math.max(1, Math.ceil(km.doubleValue() / CITY_SPEED_KMH * 60));
    }

    private java.util.Optional<Row> row(UUID bookingId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_BOOKING_TRACKING)).param("id", bookingId).query(Row.class).optional();
    }

    private static TrackingDto toDto(Row r) {
        boolean active = "ON_THE_WAY".equals(r.status());
        return new TrackingDto(r.bookingId(), r.status(), active, r.travelStartedAt(),
            active ? r.lat() : null, active ? r.lng() : null, active ? r.accuracyM() : null,
            active ? r.distanceKm() : null, active ? r.distanceMethod() : null, active ? r.etaMinutes() : null,
            active ? r.updatedAt() : null, r.customerLat(), r.customerLng());
    }
}
