package com.progenie.booking;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.progenie.booking.BookingDtos.AddressView;
import com.progenie.booking.BookingDtos.BookingDetailDto;
import com.progenie.booking.BookingDtos.BookingPhotoDto;
import com.progenie.booking.BookingDtos.BookingSummaryDto;
import com.progenie.booking.BookingDtos.HistoryDto;
import com.progenie.booking.BookingDtos.PartyDto;
import com.progenie.booking.domain.BookingStatus;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.Times;
import com.progenie.shared.web.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Booking read models for customers, Genies and admins (plain SQL, privacy rules applied here). */
@Service
public class BookingQueryService {

    public enum Viewer { CUSTOMER, GENIE, ADMIN }

    /** Customer and Genie booking tabs. */
    public enum Scope { ACTIVE, REQUESTS, UPCOMING, PAST, ALL }

    static final Duration REVIEW_WINDOW = Duration.ofDays(14);
    private static final Set<String> CONTACT_VISIBLE = Set.of("ACCEPTED", "ON_THE_WAY", "IN_PROGRESS");
    private static final Set<String> ADDRESS_VISIBLE = Set.of("ACCEPTED", "ON_THE_WAY", "IN_PROGRESS", "COMPLETED");

    /** Flat SQL row behind {@link BookingDetailDto}. */
    record Row(UUID id, String bookingRef, String status, UUID customerId, UUID genieId, long serviceId,
               String serviceName, String categoryName, String categorySlug, String genieName, String geniePhone,
               BigDecimal genieRating, String customerName, String customerPhone, String addrLabel, String addrLine1,
               String addrLine2, String addrLandmark, String addrArea, String addrCity, String addrPincode,
               OffsetDateTime slotStart, OffsetDateTime slotEnd, OffsetDateTime expiresAt, String notes,
               BigDecimal serviceAmount, BigDecimal extraAmount, String extraNote, BigDecimal distanceKm,
               BigDecimal travelFee, BigDecimal tipAmount, BigDecimal totalAmount, BigDecimal commissionAmount,
               BigDecimal geniePayout, String paymentMethod, String paymentStatus, BigDecimal cancellationFee,
               String cancellationFeeStatus, String cancelReason, String cancelledByRole, int rescheduledCount,
               boolean reviewed, OffsetDateTime createdAt, OffsetDateTime acceptedAt, OffsetDateTime startedAt,
               OffsetDateTime completedAt, OffsetDateTime cancelledAt, BigDecimal discountAmount, String couponCode,
               OffsetDateTime travelStartedAt, BigDecimal instantFee, boolean instant) {
    }

    /** Status filter per list tab: keys of SQL fragments in sql_scripts.properties. */
    private static final Map<Scope, String> SCOPE_FILTER = Map.of(
        Scope.ACTIVE, QueryConstants.FRAGMENT_BOOKING_SCOPE_ACTIVE,
        Scope.REQUESTS, QueryConstants.FRAGMENT_BOOKING_SCOPE_REQUESTS,
        Scope.UPCOMING, QueryConstants.FRAGMENT_BOOKING_SCOPE_UPCOMING,
        Scope.PAST, QueryConstants.FRAGMENT_BOOKING_SCOPE_PAST,
        Scope.ALL, QueryConstants.FRAGMENT_BOOKING_SCOPE_ALL);

    private final JdbcClient jdbc;
    private final AppProperties.Booking rules;
    private final Clock clock;
    private final BookingPhotos photos;

    public BookingQueryService(JdbcClient jdbc, AppProperties props, Clock clock, BookingPhotos photos) {
        this.jdbc = jdbc;
        this.rules = props.booking();
        this.clock = clock;
        this.photos = photos;
    }

    // ------------------------------------------------------------------ lists

    @Transactional(readOnly = true)
    public PageResponse<BookingSummaryDto> customerBookings(UUID customerId, Scope scope, int page, int size) {
        return list(QueryConstants.FRAGMENT_BOOKING_COUNTERPART_GENIE, QueryConstants.FRAGMENT_BOOKING_OWNER_CUSTOMER,
            customerId, scope, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingSummaryDto> genieBookings(UUID genieId, Scope scope, int page, int size) {
        return list(QueryConstants.FRAGMENT_BOOKING_COUNTERPART_CUSTOMER, QueryConstants.FRAGMENT_BOOKING_OWNER_GENIE,
            genieId, scope, page, size);
    }

    /** {@code counterpart} and {@code owner} are fragment keys: whose name to show and whose bookings to list. */
    private PageResponse<BookingSummaryDto> list(String counterpart, String owner, UUID ownerId, Scope scope,
                                                 int page, int size) {
        String scopeFilter = SCOPE_FILTER.get(scope);
        // Upcoming work is most useful soonest-first; history newest-first.
        String order = scope == Scope.PAST || scope == Scope.ALL
            ? QueryConstants.FRAGMENT_BOOKING_ORDER_LATEST : QueryConstants.FRAGMENT_BOOKING_ORDER_SOONEST;
        long total = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_BOOKING_LIST, Map.of("owner", owner, "scope", scopeFilter)))
            .param("owner", ownerId)
            .query(Long.class).single();
        List<BookingSummaryDto> items = jdbc.sql(QueryUtil.get(QueryConstants.GET_BOOKING_LIST,
                Map.of("counterpart", counterpart, "owner", owner, "scope", scopeFilter, "orderBy", order)))
            .param("owner", ownerId)
            .param("limit", PageResponse.clampSize(size))
            .param("offset", PageResponse.offset(page, size))
            .query(BookingSummaryDto.class)
            .list();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    /** Admin search: by status, date range and free text (booking ref, customer/Genie name or phone). */
    @Transactional(readOnly = true)
    public PageResponse<BookingSummaryDto> adminSearch(String status, String q, OffsetDateTime from, OffsetDateTime to,
                                                       int page, int size) {
        String statusFilter = StringUtils.hasText(status) ? status.trim().toUpperCase() : null;
        String query = StringUtils.hasText(q) ? "%" + q.trim() + "%" : null;
        long total = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_BOOKING_ADMIN_SEARCH))
            .param("status", statusFilter).param("from", from).param("to", to).param("q", query)
            .query(Long.class).single();
        List<BookingSummaryDto> items = jdbc.sql(QueryUtil.get(QueryConstants.GET_BOOKING_ADMIN_SEARCH,
                Map.of("counterpart", QueryConstants.FRAGMENT_BOOKING_COUNTERPART_BOTH)))
            .param("status", statusFilter).param("from", from).param("to", to).param("q", query)
            .param("limit", PageResponse.clampSize(size))
            .param("offset", PageResponse.offset(page, size))
            .query(BookingSummaryDto.class)
            .list();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    // ------------------------------------------------------------------ detail

    @Transactional(readOnly = true)
    public BookingDetailDto detail(UUID bookingId, Viewer viewer, UUID viewerId) {
        Row r = jdbc.sql(QueryUtil.get(QueryConstants.GET_BOOKING_DETAIL)).param("id", bookingId).query(Row.class).optional()
            .filter(row -> viewer == Viewer.ADMIN
                || (viewer == Viewer.CUSTOMER && row.customerId().equals(viewerId))
                || (viewer == Viewer.GENIE && row.genieId().equals(viewerId)))
            .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking not found"));

        boolean contactVisible = viewer == Viewer.ADMIN || CONTACT_VISIBLE.contains(r.status());
        boolean fullAddress = viewer != Viewer.GENIE || ADDRESS_VISIBLE.contains(r.status());
        boolean moneyVisible = viewer != Viewer.CUSTOMER;

        PartyDto genie = new PartyDto(r.genieId(), r.genieName(),
            viewer != Viewer.GENIE && contactVisible ? r.geniePhone() : null, r.genieRating());
        String customerName = viewer == Viewer.GENIE && "REQUESTED".equals(r.status())
            ? r.customerName().split(" ")[0] : r.customerName();
        PartyDto customer = new PartyDto(r.customerId(), customerName,
            viewer != Viewer.CUSTOMER && contactVisible ? r.customerPhone() : null, null);
        AddressView address = fullAddress
            ? new AddressView(r.addrLabel(), r.addrLine1(), r.addrLine2(), r.addrLandmark(), r.addrArea(), r.addrCity(), r.addrPincode())
            : new AddressView(null, null, null, null, r.addrArea(), r.addrCity(), r.addrPincode());

        Instant now = clock.instant();
        BookingStatus status = BookingStatus.valueOf(r.status());
        BigDecimal feeIfNow = viewer == Viewer.CUSTOMER && (status == BookingStatus.REQUESTED || status.isConfirmed())
            ? BookingPolicy.customerCancellationFee(status, r.slotStart().toInstant(), now,
                rules.freeCancellationWindow(), rules.lateCancellationFee())
            : null;
        List<BookingPhotoDto> jobPhotos = photos.list(bookingId);
        List<String> actions = allowedActions(r, viewer, now);
        if (viewer == Viewer.GENIE) {
            actions.addAll(photos.genieActions(r.status(), r.completedAt(), jobPhotos));
        }

        return new BookingDetailDto(r.id(), r.bookingRef(), r.status(), r.serviceId(), r.serviceName(), r.categoryName(),
            r.categorySlug(), genie, customer, address, r.slotStart(), r.slotEnd(), r.expiresAt(), r.notes(),
            r.serviceAmount(), r.extraAmount(), r.extraNote(), r.distanceKm(), r.travelFee(), r.tipAmount(),
            r.totalAmount(), moneyVisible ? r.commissionAmount() : null, moneyVisible ? r.geniePayout() : null,
            r.paymentMethod(), r.paymentStatus(), r.cancellationFee(), r.cancellationFeeStatus(), feeIfNow,
            r.cancelReason(), r.cancelledByRole(), r.rescheduledCount(), r.reviewed(), r.createdAt(), r.acceptedAt(),
            r.startedAt(), r.completedAt(), r.cancelledAt(), actions, history(bookingId), jobPhotos, r.discountAmount(),
            r.couponCode(), r.travelStartedAt(), r.instantFee(), r.instant());
    }

    List<String> allowedActions(Row r, Viewer viewer, Instant now) {
        BookingStatus status = BookingStatus.valueOf(r.status());
        Instant slotStart = r.slotStart().toInstant();
        boolean unpaid = "UNPAID".equals(r.paymentStatus());
        List<String> actions = new ArrayList<>();
        switch (viewer) {
            case CUSTOMER -> {
                if (status == BookingStatus.REQUESTED || status.isConfirmed()) {
                    actions.add("CANCEL");
                }
                if (status == BookingStatus.ON_THE_WAY) {
                    actions.add("TRACK");
                }
                if (BookingPolicy.canReschedule(status, slotStart, now, rules.freeCancellationWindow(),
                    r.rescheduledCount(), rules.maxReschedules())) {
                    actions.add("RESCHEDULE");
                }
                if (unpaid && (status.isActive() || status == BookingStatus.COMPLETED)) {
                    actions.add("CHANGE_TIP");
                }
                if (status.isConfirmed()) {
                    actions.add("START_CODE");
                }
                if (status == BookingStatus.COMPLETED && unpaid) {
                    actions.add("PAY");
                }
                if ("DUE".equals(r.cancellationFeeStatus())) {
                    actions.add("PAY_FEE");
                }
                if (status == BookingStatus.COMPLETED && !r.reviewed() && r.completedAt() != null
                    && now.isBefore(r.completedAt().toInstant().plus(REVIEW_WINDOW))) {
                    actions.add("REVIEW");
                }
            }
            case GENIE -> {
                if (status == BookingStatus.REQUESTED && (r.expiresAt() == null || now.isBefore(r.expiresAt().toInstant()))) {
                    actions.add("ACCEPT");
                    actions.add("DECLINE");
                }
                if (status == BookingStatus.ACCEPTED && BookingPolicy.canStartTravel(slotStart, now, rules.startEarlyWindow())) {
                    actions.add("START_TRAVEL");
                }
                if (status == BookingStatus.ON_THE_WAY) {
                    actions.add("SHARE_LOCATION");
                }
                if (status.isConfirmed()) {
                    if (BookingPolicy.canStart(slotStart, now, rules.startEarlyWindow())) {
                        actions.add("START");
                    }
                    actions.add("CANCEL");
                }
                if (status == BookingStatus.IN_PROGRESS) {
                    actions.add("COMPLETE");
                }
            }
            case ADMIN -> {
                if (status == BookingStatus.REQUESTED || status.isConfirmed()) {
                    actions.add("CANCEL");
                }
                if ("DUE".equals(r.cancellationFeeStatus())) {
                    actions.add("WAIVE_FEE");
                }
            }
        }
        return actions;
    }

    private List<HistoryDto> history(UUID bookingId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_BOOKING_STATUS_HISTORY))
            .param("id", bookingId)
            .query(HistoryDto.class)
            .list();
    }

    /** Used by the review module: the booking must belong to the customer and be completed. */
    public record ReviewableBooking(UUID bookingId, UUID genieId, String status, OffsetDateTime completedAt) {
    }

    @Transactional(readOnly = true)
    public ReviewableBooking reviewable(UUID customerId, UUID bookingId) {
        ReviewableBooking b = jdbc.sql(QueryUtil.get(QueryConstants.GET_BOOKING_REVIEWABLE))
            .param("id", bookingId).param("c", customerId)
            .query(ReviewableBooking.class).optional()
            .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking not found"));
        if (!"COMPLETED".equals(b.status())) {
            throw ApiException.unprocessable("NOT_COMPLETED", "You can review a booking after the job is done");
        }
        if (clock.instant().isAfter(b.completedAt().toInstant().plus(REVIEW_WINDOW))) {
            throw ApiException.unprocessable("REVIEW_WINDOW_CLOSED", "Reviews can be written up to 14 days after the job");
        }
        return b;
    }

    /** True if the customer has an unpaid late-cancellation fee (they must pay it before booking again). */
    @Transactional(readOnly = true)
    public boolean hasOutstandingFee(UUID customerId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_BOOKING_OUTSTANDING_FEE))
            .param("c", customerId).query(Boolean.class).single();
    }

    /** Genie cancellations of accepted jobs within the look-back window (reliability check). */
    @Transactional(readOnly = true)
    public int recentGenieCancellations(UUID genieId, Duration window) {
        return jdbc.sql(QueryUtil.get(QueryConstants.COUNT_BOOKING_RECENT_GENIE_CANCELLATIONS))
            .param("g", genieId).param("since", Times.odt(clock.instant().minus(window)))
            .query(Integer.class).single();
    }
}
