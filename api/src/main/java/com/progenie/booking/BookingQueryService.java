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
import com.progenie.booking.BookingDtos.BookingSummaryDto;
import com.progenie.booking.BookingDtos.HistoryDto;
import com.progenie.booking.BookingDtos.PartyDto;
import com.progenie.booking.domain.BookingStatus;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.error.ApiException;
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
    private static final Set<String> CONTACT_VISIBLE = Set.of("ACCEPTED", "IN_PROGRESS");
    private static final Set<String> ADDRESS_VISIBLE = Set.of("ACCEPTED", "IN_PROGRESS", "COMPLETED");

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
               OffsetDateTime completedAt, OffsetDateTime cancelledAt) {
    }

    private static final String DETAIL_SQL = """
        SELECT b.id, b.booking_ref, b.status, b.customer_id, b.genie_id, b.service_id,
               s.name AS service_name, c.name AS category_name, c.slug AS category_slug,
               g.full_name AS genie_name, g.phone AS genie_phone, gp.avg_rating AS genie_rating,
               cu.full_name AS customer_name, cu.phone AS customer_phone,
               b.address_snapshot ->> 'label' AS addr_label, b.address_snapshot ->> 'line1' AS addr_line1,
               b.address_snapshot ->> 'line2' AS addr_line2, b.address_snapshot ->> 'landmark' AS addr_landmark,
               b.address_snapshot ->> 'area' AS addr_area, b.address_snapshot ->> 'city' AS addr_city,
               b.address_snapshot ->> 'pincode' AS addr_pincode,
               b.slot_start, b.slot_end, b.expires_at, b.notes, b.service_amount, b.extra_amount, b.extra_note,
               b.distance_km, b.travel_fee, b.tip_amount, b.total_amount, b.commission_amount, b.genie_payout,
               b.payment_method, b.payment_status, b.cancellation_fee, b.cancellation_fee_status, b.cancel_reason,
               cb.role AS cancelled_by_role, b.rescheduled_count,
               EXISTS (SELECT 1 FROM reviews r WHERE r.booking_id = b.id) AS reviewed,
               b.created_at, b.accepted_at, b.started_at, b.completed_at, b.cancelled_at
          FROM bookings b
          JOIN services s            ON s.id = b.service_id
          JOIN service_categories c  ON c.id = s.category_id
          JOIN users g               ON g.id = b.genie_id
          JOIN genie_profiles gp     ON gp.user_id = b.genie_id
          JOIN users cu              ON cu.id = b.customer_id
          LEFT JOIN users cb         ON cb.id = b.cancelled_by
         WHERE b.id = :id
        """;

    private static final String SUMMARY_SELECT = """
        SELECT b.id, b.booking_ref, b.status, s.name AS service_name, c.slug AS category_slug,
               %s AS counterpart_name, b.slot_start, b.slot_end, b.total_amount, b.payment_method,
               b.payment_status, b.address_snapshot ->> 'area' AS area, b.cancellation_fee_status,
               EXISTS (SELECT 1 FROM reviews r WHERE r.booking_id = b.id) AS reviewed
          FROM bookings b
          JOIN services s           ON s.id = b.service_id
          JOIN service_categories c ON c.id = s.category_id
          JOIN users g              ON g.id = b.genie_id
          JOIN users cu             ON cu.id = b.customer_id
        """;

    private static final Map<Scope, String> SCOPE_FILTER = Map.of(
        Scope.ACTIVE, " AND b.status IN ('REQUESTED', 'ACCEPTED', 'IN_PROGRESS')",
        Scope.REQUESTS, " AND b.status = 'REQUESTED'",
        Scope.UPCOMING, " AND b.status IN ('ACCEPTED', 'IN_PROGRESS')",
        Scope.PAST, " AND b.status IN ('COMPLETED', 'REJECTED', 'EXPIRED', 'CANCELLED')",
        Scope.ALL, "");

    private final JdbcClient jdbc;
    private final AppProperties.Booking rules;
    private final Clock clock;

    public BookingQueryService(JdbcClient jdbc, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.rules = props.booking();
        this.clock = clock;
    }

    // ------------------------------------------------------------------ lists

    @Transactional(readOnly = true)
    public PageResponse<BookingSummaryDto> customerBookings(UUID customerId, Scope scope, int page, int size) {
        return list("g.full_name", "b.customer_id = :owner", customerId, scope, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<BookingSummaryDto> genieBookings(UUID genieId, Scope scope, int page, int size) {
        return list("split_part(cu.full_name, ' ', 1)", "b.genie_id = :owner", genieId, scope, page, size);
    }

    private PageResponse<BookingSummaryDto> list(String counterpart, String ownerClause, UUID ownerId, Scope scope,
                                                 int page, int size) {
        String where = " WHERE " + ownerClause + SCOPE_FILTER.get(scope);
        // Upcoming work is most useful soonest-first; history newest-first.
        String order = scope == Scope.PAST || scope == Scope.ALL ? " ORDER BY b.slot_start DESC" : " ORDER BY b.slot_start ASC";
        long total = jdbc.sql("SELECT count(*) FROM bookings b" + where).param("owner", ownerId)
            .query(Long.class).single();
        List<BookingSummaryDto> items = jdbc.sql(SUMMARY_SELECT.formatted(counterpart) + where + order
                + " LIMIT :limit OFFSET :offset")
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
        String where = """
             WHERE (CAST(:status AS varchar) IS NULL OR b.status = :status)
               AND (CAST(:from AS timestamptz) IS NULL OR b.slot_start >= :from)
               AND (CAST(:to AS timestamptz) IS NULL OR b.slot_start < :to)
               AND (CAST(:q AS varchar) IS NULL OR b.booking_ref ILIKE :q OR g.full_name ILIKE :q OR cu.full_name ILIKE :q
                    OR g.phone LIKE :q OR cu.phone LIKE :q)
            """;
        String statusFilter = StringUtils.hasText(status) ? status.trim().toUpperCase() : null;
        String query = StringUtils.hasText(q) ? "%" + q.trim() + "%" : null;
        long total = jdbc.sql("""
                SELECT count(*) FROM bookings b JOIN users g ON g.id = b.genie_id JOIN users cu ON cu.id = b.customer_id
                """ + where)
            .param("status", statusFilter).param("from", from).param("to", to).param("q", query)
            .query(Long.class).single();
        List<BookingSummaryDto> items = jdbc.sql(SUMMARY_SELECT.formatted("g.full_name || ' → ' || cu.full_name") + where
                + " ORDER BY b.created_at DESC LIMIT :limit OFFSET :offset")
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
        Row r = jdbc.sql(DETAIL_SQL).param("id", bookingId).query(Row.class).optional()
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
        BigDecimal feeIfNow = viewer == Viewer.CUSTOMER && (status == BookingStatus.REQUESTED || status == BookingStatus.ACCEPTED)
            ? BookingPolicy.customerCancellationFee(status, r.slotStart().toInstant(), now,
                rules.freeCancellationWindow(), rules.lateCancellationFee())
            : null;

        return new BookingDetailDto(r.id(), r.bookingRef(), r.status(), r.serviceId(), r.serviceName(), r.categoryName(),
            r.categorySlug(), genie, customer, address, r.slotStart(), r.slotEnd(), r.expiresAt(), r.notes(),
            r.serviceAmount(), r.extraAmount(), r.extraNote(), r.distanceKm(), r.travelFee(), r.tipAmount(),
            r.totalAmount(), moneyVisible ? r.commissionAmount() : null, moneyVisible ? r.geniePayout() : null,
            r.paymentMethod(), r.paymentStatus(), r.cancellationFee(), r.cancellationFeeStatus(), feeIfNow,
            r.cancelReason(), r.cancelledByRole(), r.rescheduledCount(), r.reviewed(), r.createdAt(), r.acceptedAt(),
            r.startedAt(), r.completedAt(), r.cancelledAt(), allowedActions(r, viewer, now), history(bookingId));
    }

    List<String> allowedActions(Row r, Viewer viewer, Instant now) {
        BookingStatus status = BookingStatus.valueOf(r.status());
        Instant slotStart = r.slotStart().toInstant();
        boolean unpaid = "UNPAID".equals(r.paymentStatus());
        List<String> actions = new ArrayList<>();
        switch (viewer) {
            case CUSTOMER -> {
                if (status == BookingStatus.REQUESTED || status == BookingStatus.ACCEPTED) {
                    actions.add("CANCEL");
                }
                if (BookingPolicy.canReschedule(status, slotStart, now, rules.freeCancellationWindow(),
                    r.rescheduledCount(), rules.maxReschedules())) {
                    actions.add("RESCHEDULE");
                }
                if (unpaid && (status.isActive() || status == BookingStatus.COMPLETED)) {
                    actions.add("CHANGE_TIP");
                }
                if (status == BookingStatus.ACCEPTED) {
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
                if (status == BookingStatus.ACCEPTED) {
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
                if (status == BookingStatus.REQUESTED || status == BookingStatus.ACCEPTED) {
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
        return jdbc.sql("""
                SELECT h.from_status, h.to_status, coalesce(u.role, 'SYSTEM') AS actor_role, h.reason, h.changed_at
                  FROM booking_status_history h LEFT JOIN users u ON u.id = h.changed_by
                 WHERE h.booking_id = :id ORDER BY h.changed_at, h.id
                """)
            .param("id", bookingId)
            .query(HistoryDto.class)
            .list();
    }

    /** Used by the review module: the booking must belong to the customer and be completed. */
    public record ReviewableBooking(UUID bookingId, UUID genieId, String status, OffsetDateTime completedAt) {
    }

    @Transactional(readOnly = true)
    public ReviewableBooking reviewable(UUID customerId, UUID bookingId) {
        ReviewableBooking b = jdbc.sql("""
                SELECT id AS booking_id, genie_id, status, completed_at FROM bookings
                 WHERE id = :id AND customer_id = :c
                """)
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
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM bookings WHERE customer_id = :c AND cancellation_fee_status = 'DUE')")
            .param("c", customerId).query(Boolean.class).single();
    }

    /** Genie cancellations of accepted jobs within the look-back window (reliability check). */
    @Transactional(readOnly = true)
    public int recentGenieCancellations(UUID genieId, Duration window) {
        return jdbc.sql("""
                SELECT count(*) FROM bookings
                 WHERE genie_id = :g AND cancelled_by = :g AND status = 'CANCELLED' AND cancelled_at > :since
                """)
            .param("g", genieId).param("since", Times.odt(clock.instant().minus(window)))
            .query(Integer.class).single();
    }
}
