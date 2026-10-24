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
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
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
        ProfileRow p = jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_PROFILE_ME))
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
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_PROFILE_SERVICES))
            .param("id", genieId)
            .query(MyServiceDto.class)
            .list();
    }

    List<AvailabilityDto> availability(UUID genieId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_PROFILE_AVAILABILITY))
            .param("id", genieId)
            .query(AvailabilityDto.class)
            .list();
    }

    // ------------------------------------------------------------------ profile

    @Transactional
    public MyProfileDto updateProfile(UUID genieId, ProfileRequest req) {
        requireProfile(genieId);
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_GENIE_PROFILE_DETAILS))
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
        jdbc.sql(QueryUtil.get(QueryConstants.DELETE_GENIE_PROFILE_SERVICE_AREAS)).param("id", genieId).update();
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_GENIE_PROFILE_SERVICE_AREAS))
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
        Map<Long, BigDecimal> basePrices = jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_PROFILE_ACTIVE_SERVICES_BY_IDS))
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
        jdbc.sql(QueryUtil.get(QueryConstants.DELETE_GENIE_PROFILE_SERVICES)).param("id", genieId).update();
        for (ServiceOfferRequest offer : offers) {
            jdbc.sql(QueryUtil.get(QueryConstants.INSERT_GENIE_PROFILE_SERVICE))
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
        jdbc.sql(QueryUtil.get(QueryConstants.DELETE_GENIE_PROFILE_AVAILABILITY)).param("id", genieId).update();
        for (AvailabilityDto w : sorted) {
            jdbc.sql(QueryUtil.get(QueryConstants.INSERT_GENIE_PROFILE_AVAILABILITY))
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
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_PROFILE_UPCOMING_TIME_OFF))
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
        return jdbc.sql(QueryUtil.get(QueryConstants.INSERT_GENIE_PROFILE_TIME_OFF))
            .param("g", genieId)
            .param("s", Times.odt(start))
            .param("e", Times.odt(end))
            .param("r", trimToNull(req.reason()))
            .query(TimeOffDto.class)
            .single();
    }

    @Transactional
    public void deleteTimeOff(UUID genieId, long timeOffId) {
        int rows = jdbc.sql(QueryUtil.get(QueryConstants.DELETE_GENIE_PROFILE_TIME_OFF))
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
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_GENIE_PROFILE_SUBMIT))
            .param("id", genieId).update();
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_GENIE_VERIFICATION_SUBMITTED))
            .param("id", genieId).param("from", me.verificationStatus()).update();
        events.publishEvent(new GenieSubmitted(genieId, me.fullName()));
        return me(genieId);
    }

    @Transactional
    public boolean setOnline(UUID genieId, boolean online) {
        String status = jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_PROFILE_STATUS))
            .param("id", genieId).query(String.class).optional()
            .orElseThrow(() -> ApiException.notFound("GENIE_PROFILE_NOT_FOUND", "Genie profile not found"));
        if (online && !"APPROVED".equals(status)) {
            throw ApiException.unprocessable("NOT_APPROVED", "You can go online after an admin approves your profile");
        }
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_GENIE_PROFILE_ONLINE))
            .param("online", online).param("id", genieId).update();
        return online;
    }

    @Transactional
    public void updateLocation(UUID genieId, LocationRequest req) {
        requireProfile(genieId);
        jdbc.sql(QueryUtil.get(QueryConstants.UPSERT_GENIE_PROFILE_LOCATION))
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
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_GENIE_PROFILE_COMPLETED_JOB))
            .param("id", genieId).update();
    }

    /** Booking module: a Genie cancelled a job they had accepted. */
    @Transactional
    public void recordCancellation(UUID genieId) {
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_GENIE_PROFILE_CANCELLATION))
            .param("id", genieId).update();
    }

    /** Booking module: too many cancellations; flag once until an admin clears it. */
    @Transactional
    public void flag(UUID genieId, String reason) {
        int rows = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_GENIE_PROFILE_FLAG))
            .param("reason", reason).param("id", genieId).update();
        if (rows > 0) {
            String name = jdbc.sql(QueryUtil.get(QueryConstants.GET_USER_FULL_NAME)).param("id", genieId)
                .query(String.class).single();
            events.publishEvent(new GenieEvents.GenieFlagged(genieId, name, reason));
        }
    }

    /** Review module: recompute the denormalised rating after a new review. */
    @Transactional
    public void refreshRating(UUID genieId) {
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_GENIE_PROFILE_RATING))
            .param("id", genieId).update();
    }

    void requireProfile(UUID genieId) {
        boolean exists = jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_GENIE_PROFILE_BY_ID))
            .param("id", genieId).query(Boolean.class).single();
        if (!exists) {
            throw ApiException.notFound("GENIE_PROFILE_NOT_FOUND", "Genie profile not found");
        }
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
