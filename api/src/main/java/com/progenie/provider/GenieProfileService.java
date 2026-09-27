package com.progenie.provider;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.progenie.identity.AccountGuards;
import com.progenie.provider.GenieEvents.GenieSubmitted;
import com.progenie.provider.GenieProfileDtos.AvailabilityDto;
import com.progenie.provider.GenieProfileDtos.DocumentDto;
import com.progenie.provider.GenieProfileDtos.LocationRequest;
import com.progenie.provider.GenieProfileDtos.MyProfileDto;
import com.progenie.provider.GenieProfileDtos.MyServiceDto;
import com.progenie.provider.GenieProfileDtos.ProfileRequest;
import com.progenie.provider.GenieProfileDtos.ProfileRow;
import com.progenie.provider.GenieProfileDtos.ServiceOfferRequest;
import com.progenie.provider.GenieProfileDtos.Step;
import com.progenie.provider.GenieProfileDtos.TimeOffDto;
import com.progenie.provider.GenieProfileDtos.TimeOffRequest;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.Times;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * A Genie's own profile: onboarding steps, services and prices, weekly availability, time off,
 * go-online switch and live location. Verification decisions live in {@link GenieVerificationService}.
 */
@Service
public class GenieProfileService {

    /** Genies may set their own price between half and three times the platform's base price. */
    static final BigDecimal MIN_PRICE_FACTOR = new BigDecimal("0.5");
    static final BigDecimal MAX_PRICE_FACTOR = new BigDecimal("3.0");
    static final int MIN_BIO_LENGTH = 20;
    private static final Duration MAX_TIME_OFF = Duration.ofDays(60);

    private final JdbcClient jdbc;
    private final GenieDocumentService documents;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final AccountGuards guards;

    public GenieProfileService(JdbcClient jdbc, GenieDocumentService documents, ApplicationEventPublisher events, Clock clock,
                               AccountGuards guards) {
        this.guards = guards;
        this.jdbc = jdbc;
        this.documents = documents;
        this.events = events;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ read

    @Transactional(readOnly = true)
    public MyProfileDto me(UUID genieId) {
        ProfileRow p = jdbc.sql("""
                SELECT u.id, u.full_name, u.phone, u.email, gp.bio, gp.experience_years, gp.verification_status,
                       gp.verification_note, gp.base_area,
                       ST_Y(gp.base_location::geometry) AS base_lat, ST_X(gp.base_location::geometry) AS base_lng,
                       gp.service_radius_km, gp.payout_upi_id, gp.is_online AS online, gp.avg_rating, gp.rating_count,
                       gp.completed_jobs, gp.cancellation_count, gp.submitted_at, gp.approved_at
                  FROM genie_profiles gp
                  JOIN users u ON u.id = gp.user_id
                 WHERE gp.user_id = :id
                """)
            .param("id", genieId)
            .query(ProfileRow.class)
            .optional()
            .orElseThrow(() -> ApiException.notFound("GENIE_PROFILE_NOT_FOUND", "Genie profile not found"));

        List<MyServiceDto> services = services(genieId);
        List<AvailabilityDto> availability = availability(genieId);
        List<DocumentDto> docs = documents.list(genieId);
        List<Step> missing = missingSteps(p, services, availability, docs);
        boolean canSubmit = missing.isEmpty() && Set.of("REGISTERED", "NEEDS_CHANGES").contains(p.verificationStatus());
        return new MyProfileDto(p.id(), p.fullName(), p.phone(), p.email(), p.bio(), p.experienceYears(),
            p.verificationStatus(), p.verificationNote(), p.baseArea(), p.baseLat(), p.baseLng(), p.serviceRadiusKm(),
            p.payoutUpiId(), p.online(), p.avgRating(), p.ratingCount(), p.completedJobs(), p.cancellationCount(),
            p.submittedAt(), p.approvedAt(), services, availability, docs, missing, canSubmit);
    }

    static List<Step> missingSteps(ProfileRow p, List<MyServiceDto> services, List<AvailabilityDto> availability,
                                   List<DocumentDto> docs) {
        EnumSet<Step> missing = EnumSet.noneOf(Step.class);
        if (p.bio() == null || p.bio().trim().length() < MIN_BIO_LENGTH) {
            missing.add(Step.PROFILE);
        }
        if (p.baseLat() == null || !StringUtils.hasText(p.baseArea())) {
            missing.add(Step.LOCATION);
        }
        if (services.isEmpty()) {
            missing.add(Step.SERVICES);
        }
        if (availability.isEmpty()) {
            missing.add(Step.AVAILABILITY);
        }
        if (docs.stream().noneMatch(d -> Set.of("AADHAAR", "PAN").contains(d.docType()) && !"REJECTED".equals(d.status()))) {
            missing.add(Step.ID_DOCUMENT);
        }
        if (docs.stream().noneMatch(d -> "SELFIE".equals(d.docType()) && !"REJECTED".equals(d.status()))) {
            missing.add(Step.SELFIE);
        }
        if (!StringUtils.hasText(p.payoutUpiId())) {
            missing.add(Step.PAYOUT);
        }
        return List.copyOf(missing);
    }

    List<MyServiceDto> services(UUID genieId) {
        return jdbc.sql("""
                SELECT s.id AS service_id, s.name, c.name AS category_name, s.base_price, gs.price_override,
                       coalesce(gs.price_override, s.base_price) AS price, s.duration_minutes
                  FROM genie_services gs
                  JOIN services s           ON s.id = gs.service_id
                  JOIN service_categories c ON c.id = s.category_id
                 WHERE gs.genie_id = :id AND gs.is_active
                 ORDER BY c.sort_order, s.base_price
                """)
            .param("id", genieId)
            .query(MyServiceDto.class)
            .list();
    }

    List<AvailabilityDto> availability(UUID genieId) {
        return jdbc.sql("""
                SELECT day_of_week, start_time, end_time FROM genie_availability
                 WHERE genie_id = :id ORDER BY day_of_week, start_time
                """)
            .param("id", genieId)
            .query(AvailabilityDto.class)
            .list();
    }

    // ------------------------------------------------------------------ profile

    @Transactional
    public MyProfileDto updateProfile(UUID genieId, ProfileRequest req) {
        requireProfile(genieId);
        jdbc.sql("""
                UPDATE genie_profiles
                   SET bio = :bio, experience_years = :experience, base_area = :area,
                       base_location = ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                       service_radius_km = :radius, payout_upi_id = :upi, updated_at = now()
                 WHERE user_id = :id
                """)
            .param("bio", trimToNull(req.bio()))
            .param("experience", req.experienceYears())
            .param("area", trimToNull(req.baseArea()))
            .param("lat", req.baseLat())
            .param("lng", req.baseLng())
            .param("radius", req.serviceRadiusKm())
            .param("upi", trimToNull(req.payoutUpiId()))
            .param("id", genieId)
            .update();
        // The service area (and so the city pricing used for quotes) is the nearest active city.
        jdbc.sql("DELETE FROM genie_service_areas WHERE genie_id = :id").param("id", genieId).update();
        jdbc.sql("""
                INSERT INTO genie_service_areas (genie_id, city_id)
                SELECT :id, c.id FROM cities c
                 WHERE c.is_active AND c.center IS NOT NULL
                 ORDER BY ST_Distance(c.center, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography)
                 LIMIT 1
                """)
            .param("id", genieId)
            .param("lat", req.baseLat())
            .param("lng", req.baseLng())
            .update();
        return me(genieId);
    }

    @Transactional
    public List<MyServiceDto> replaceServices(UUID genieId, List<ServiceOfferRequest> offers) {
        requireProfile(genieId);
        Set<Long> seen = new HashSet<>();
        for (ServiceOfferRequest offer : offers) {
            if (!seen.add(offer.serviceId())) {
                throw ApiException.badRequest("DUPLICATE_SERVICE", "Each service can be listed only once");
            }
        }
        record Base(long id, BigDecimal basePrice) {
        }
        Map<Long, BigDecimal> basePrices = jdbc.sql("SELECT id, base_price FROM services WHERE is_active AND id IN (:ids)")
            .param("ids", seen)
            .query(Base.class)
            .list().stream()
            .collect(Collectors.toMap(Base::id, Base::basePrice));
        for (ServiceOfferRequest offer : offers) {
            BigDecimal base = basePrices.get(offer.serviceId());
            if (base == null) {
                throw ApiException.badRequest("SERVICE_NOT_FOUND", "Service " + offer.serviceId() + " is not available");
            }
            if (offer.priceOverride() != null
                && (offer.priceOverride().compareTo(base.multiply(MIN_PRICE_FACTOR)) < 0
                    || offer.priceOverride().compareTo(base.multiply(MAX_PRICE_FACTOR)) > 0)) {
                throw ApiException.badRequest("PRICE_OUT_OF_RANGE",
                    "Your price must be between 0.5× and 3× the base price (" + base + ")");
            }
        }
        jdbc.sql("DELETE FROM genie_services WHERE genie_id = :id").param("id", genieId).update();
        for (ServiceOfferRequest offer : offers) {
            jdbc.sql("INSERT INTO genie_services (genie_id, service_id, price_override) VALUES (:g, :s, :p)")
                .param("g", genieId)
                .param("s", offer.serviceId())
                .param("p", offer.priceOverride())
                .update();
        }
        return services(genieId);
    }

    @Transactional
    public List<AvailabilityDto> replaceAvailability(UUID genieId, List<AvailabilityDto> windows) {
        requireProfile(genieId);
        List<AvailabilityDto> sorted = new ArrayList<>(windows);
        sorted.sort(Comparator.comparingInt(AvailabilityDto::dayOfWeek).thenComparing(AvailabilityDto::startTime));
        for (int i = 0; i < sorted.size(); i++) {
            AvailabilityDto w = sorted.get(i);
            if (w.dayOfWeek() < 1 || w.dayOfWeek() > 7 || w.startTime() == null || w.endTime() == null) {
                throw ApiException.badRequest("INVALID_AVAILABILITY", "Day must be 1 (Mon) to 7 (Sun) with start and end times");
            }
            if (!w.startTime().isBefore(w.endTime())) {
                throw ApiException.badRequest("INVALID_AVAILABILITY", "Start time must be before end time");
            }
            if (w.startTime().getMinute() % 15 != 0 || w.endTime().getMinute() % 15 != 0) {
                throw ApiException.badRequest("INVALID_AVAILABILITY", "Times must be on a 15-minute boundary");
            }
            if (i > 0 && sorted.get(i - 1).dayOfWeek() == w.dayOfWeek() && sorted.get(i - 1).endTime().isAfter(w.startTime())) {
                throw ApiException.badRequest("OVERLAPPING_AVAILABILITY", "Two time windows overlap on the same day");
            }
        }
        jdbc.sql("DELETE FROM genie_availability WHERE genie_id = :id").param("id", genieId).update();
        for (AvailabilityDto w : sorted) {
            jdbc.sql("""
                    INSERT INTO genie_availability (genie_id, day_of_week, start_time, end_time)
                    VALUES (:g, :d, :s, :e)
                    """)
                .param("g", genieId)
                .param("d", w.dayOfWeek())
                .param("s", w.startTime())
                .param("e", w.endTime())
                .update();
        }
        return availability(genieId);
    }

    // ------------------------------------------------------------------ time off

    @Transactional(readOnly = true)
    public List<TimeOffDto> timeOff(UUID genieId) {
        return jdbc.sql("""
                SELECT id, starts_at, ends_at, reason FROM genie_time_off
                 WHERE genie_id = :id AND ends_at > now() ORDER BY starts_at
                """)
            .param("id", genieId)
            .query(TimeOffDto.class)
            .list();
    }

    @Transactional
    public TimeOffDto addTimeOff(UUID genieId, TimeOffRequest req) {
        requireProfile(genieId);
        Instant start = req.startsAt().toInstant();
        Instant end = req.endsAt().toInstant();
        if (!start.isBefore(end)) {
            throw ApiException.badRequest("INVALID_TIME_OFF", "Start must be before end");
        }
        if (!end.isAfter(clock.instant())) {
            throw ApiException.badRequest("INVALID_TIME_OFF", "Time off must end in the future");
        }
        if (Duration.between(start, end).compareTo(MAX_TIME_OFF) > 0) {
            throw ApiException.badRequest("INVALID_TIME_OFF", "Time off can be at most 60 days at a time");
        }
        return jdbc.sql("""
                INSERT INTO genie_time_off (genie_id, starts_at, ends_at, reason) VALUES (:g, :s, :e, :r)
                RETURNING id, starts_at, ends_at, reason
                """)
            .param("g", genieId)
            .param("s", Times.odt(start))
            .param("e", Times.odt(end))
            .param("r", trimToNull(req.reason()))
            .query(TimeOffDto.class)
            .single();
    }

    @Transactional
    public void deleteTimeOff(UUID genieId, long timeOffId) {
        int rows = jdbc.sql("DELETE FROM genie_time_off WHERE id = :id AND genie_id = :g")
            .param("id", timeOffId).param("g", genieId).update();
        if (rows == 0) {
            throw ApiException.notFound("TIME_OFF_NOT_FOUND", "Time off not found");
        }
    }

    // ------------------------------------------------------------------ verification submit / online / location

    @Transactional
    public MyProfileDto submitForReview(UUID genieId) {
        guards.requireVerifiedPhone(genieId);
        MyProfileDto me = me(genieId);
        if (!me.canSubmit()) {
            if (!me.missingSteps().isEmpty()) {
                throw ApiException.unprocessable("ONBOARDING_INCOMPLETE",
                    "Please complete: " + me.missingSteps().stream().map(Enum::name).collect(Collectors.joining(", ")));
            }
            throw ApiException.unprocessable("INVALID_STATE", "Your profile is " + me.verificationStatus());
        }
        jdbc.sql("""
                UPDATE genie_profiles SET verification_status = 'UNDER_REVIEW', submitted_at = now(), updated_at = now()
                 WHERE user_id = :id
                """)
            .param("id", genieId).update();
        jdbc.sql("""
                INSERT INTO genie_verification_events (genie_id, from_status, to_status, reason, actor_id)
                VALUES (:id, :from, 'UNDER_REVIEW', 'Submitted for review', :id)
                """)
            .param("id", genieId).param("from", me.verificationStatus()).update();
        events.publishEvent(new GenieSubmitted(genieId, me.fullName()));
        return me(genieId);
    }

    @Transactional
    public boolean setOnline(UUID genieId, boolean online) {
        String status = jdbc.sql("SELECT verification_status FROM genie_profiles WHERE user_id = :id")
            .param("id", genieId).query(String.class).optional()
            .orElseThrow(() -> ApiException.notFound("GENIE_PROFILE_NOT_FOUND", "Genie profile not found"));
        if (online && !"APPROVED".equals(status)) {
            throw ApiException.unprocessable("NOT_APPROVED", "You can go online after an admin approves your profile");
        }
        jdbc.sql("UPDATE genie_profiles SET is_online = :online, updated_at = now() WHERE user_id = :id")
            .param("online", online).param("id", genieId).update();
        return online;
    }

    @Transactional
    public void updateLocation(UUID genieId, LocationRequest req) {
        requireProfile(genieId);
        jdbc.sql("""
                INSERT INTO genie_locations (genie_id, location, accuracy_m, updated_at)
                VALUES (:id, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :acc, now())
                ON CONFLICT (genie_id) DO UPDATE SET location = EXCLUDED.location, accuracy_m = EXCLUDED.accuracy_m,
                                                     updated_at = now()
                """)
            .param("id", genieId)
            .param("lat", req.lat())
            .param("lng", req.lng())
            .param("acc", req.accuracyM())
            .update();
    }

    // ------------------------------------------------------------------ used by other modules

    /** Booking module: a Genie finished a job. */
    @Transactional
    public void recordCompletedJob(UUID genieId) {
        jdbc.sql("UPDATE genie_profiles SET completed_jobs = completed_jobs + 1, updated_at = now() WHERE user_id = :id")
            .param("id", genieId).update();
    }

    /** Booking module: a Genie cancelled a job they had accepted. */
    @Transactional
    public void recordCancellation(UUID genieId) {
        jdbc.sql("UPDATE genie_profiles SET cancellation_count = cancellation_count + 1, updated_at = now() WHERE user_id = :id")
            .param("id", genieId).update();
    }

    /** Booking module: too many cancellations; flag once until an admin clears it. */
    @Transactional
    public void flag(UUID genieId, String reason) {
        int rows = jdbc.sql("""
                UPDATE genie_profiles SET flagged_at = now(), flag_reason = :reason, updated_at = now()
                 WHERE user_id = :id AND flagged_at IS NULL
                """)
            .param("reason", reason).param("id", genieId).update();
        if (rows > 0) {
            String name = jdbc.sql("SELECT full_name FROM users WHERE id = :id").param("id", genieId)
                .query(String.class).single();
            events.publishEvent(new GenieEvents.GenieFlagged(genieId, name, reason));
        }
    }

    /** Review module: recompute the denormalised rating after a new review. */
    @Transactional
    public void refreshRating(UUID genieId) {
        jdbc.sql("""
                UPDATE genie_profiles gp
                   SET avg_rating = coalesce(r.avg_rating, 0), rating_count = coalesce(r.cnt, 0), updated_at = now()
                  FROM (SELECT round(avg(rating)::numeric, 1) AS avg_rating, count(*) AS cnt
                          FROM reviews WHERE genie_id = :id) r
                 WHERE gp.user_id = :id
                """)
            .param("id", genieId).update();
    }

    void requireProfile(UUID genieId) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM genie_profiles WHERE user_id = :id)")
            .param("id", genieId).query(Boolean.class).single();
        if (!exists) {
            throw ApiException.notFound("GENIE_PROFILE_NOT_FOUND", "Genie profile not found");
        }
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
