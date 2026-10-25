package com.progenie.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.progenie.shared.config.AppProperties;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.storage.FileStorage;
import com.progenie.shared.util.FileTypes;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.Times;
import com.progenie.shared.web.PageResponse;
import com.progenie.support.TicketEvents.TicketRaised;
import com.progenie.support.TicketEvents.TicketReplied;
import com.progenie.support.TicketEvents.TicketResolved;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

/**
 * Booking complaints ("Report a problem") and privacy requests, as seen by the customer and the Genie
 * (design doc 16.4). A report can be raised up to 7 days after the slot; one open ticket per booking.
 * Both sides of the booking see the ticket and can reply; admin notes marked internal stay hidden.
 */
@Service
public class TicketService {

    private static final Logger log = LoggerFactory.getLogger(TicketService.class);
    static final Duration REPORT_WINDOW = Duration.ofDays(7);
    static final Duration REOPEN_WINDOW = Duration.ofDays(7);
    static final int MAX_PHOTOS = 5;
    static final long MAX_PHOTO_BYTES = 5L * 1024 * 1024;
    static final List<String> OPEN = List.of("OPEN", "IN_REVIEW", "AWAITING_REPLY");

    public record RaiseRequest(String category, String subject, String description) {
    }

    public record TicketSummary(UUID id, String ticketRef, String type, @Nullable UUID bookingId, @Nullable String bookingRef, String category,
                                String priority, String status, String subject, UUID raisedBy, String raisedByName,
                                String raisedByRole, @Nullable String assignedToName, OffsetDateTime firstResponseDue,
                                OffsetDateTime resolutionDue, boolean overdue, OffsetDateTime createdAt,
                                OffsetDateTime updatedAt) {
    }

    public record TicketMessageDto(long id, @Nullable UUID authorId, String authorName, String authorRole, String body, boolean internal,
                             OffsetDateTime createdAt) {
    }

    public record AttachmentDto(UUID id, @Nullable String fileName, String contentType, int sizeBytes, UUID uploadedBy,
                                OffsetDateTime createdAt) {
    }

    /** {@code can*} flags tell the app which buttons to show for the viewer. */
    public record TicketDetail(UUID id, String ticketRef, String type, @Nullable UUID bookingId, @Nullable String bookingRef, @Nullable String serviceName,
                               @Nullable UUID customerId, @Nullable String customerName, @Nullable UUID genieId, @Nullable String genieName, String category,
                               String priority, String status, String subject, String description, UUID raisedBy,
                               String raisedByName, String raisedByRole, @Nullable UUID assignedTo, @Nullable String assignedToName,
                               OffsetDateTime firstResponseDue, OffsetDateTime resolutionDue, boolean overdue,
                               @Nullable OffsetDateTime firstRespondedAt, @Nullable OffsetDateTime resolvedAt, @Nullable OffsetDateTime closedAt,
                               @Nullable String resolutionActions, @Nullable String resolutionNote, @Nullable UUID refundId, @Nullable UUID redoBookingId,
                               @Nullable String redoBookingRef, OffsetDateTime createdAt, List<TicketMessageDto> messages,
                               List<AttachmentDto> attachments, boolean canReply, boolean canAddPhotos,
                               boolean canAccept, boolean canReopen) {
    }

    /** The row plus the booking parties, for access checks and events. */
    record Ticket(UUID id, String ticketRef, String type, UUID bookingId, String bookingRef, UUID customerId,
                  UUID genieId, UUID raisedBy, String raisedByRole, String category, String priority, String status,
                  String subject, OffsetDateTime firstRespondedAt, OffsetDateTime resolvedAt, int version) {

        boolean isParty(UUID userId) {
            return userId.equals(raisedBy) || userId.equals(customerId) || userId.equals(genieId);
        }

        boolean isOpen() {
            return OPEN.contains(status);
        }
    }

    record BookingParties(UUID id, String bookingRef, UUID customerId, UUID genieId, String status,
                          OffsetDateTime slotStart, OffsetDateTime slotEnd, OffsetDateTime acceptedAt) {
    }

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final ZoneId zone;

    public TicketService(JdbcClient jdbc, FileStorage storage, ApplicationEventPublisher events, Clock clock,
                         AppProperties props) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.events = events;
        this.clock = clock;
        this.zone = ZoneId.of(props.timezone());
    }

    // ------------------------------------------------------------------ raising

    /** Customer or Genie reports a problem with a booking. */
    @Transactional
    public TicketDetail raise(UUID userId, String role, UUID bookingId, RaiseRequest req) {
        TicketCategory category = parseCategory(req.category());
        if (category == TicketCategory.PRIVACY) {
            throw ApiException.badRequest("INVALID_CATEGORY", "Privacy requests are raised from Profile → Privacy");
        }
        String subject = required(req.subject(), 150, "subject");
        String description = required(req.description(), 2000, "description");
        BookingParties b = jdbc.sql(QueryUtil.get(QueryConstants.LOCK_TICKET_BOOKING_FOR_RAISE))
            .param("id", bookingId).query(BookingParties.class).optional()
            .filter(x -> userId.equals(x.customerId()) || userId.equals(x.genieId()))
            .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking not found"));
        Instant now = clock.instant();
        if (b.acceptedAt() == null) {
            throw ApiException.unprocessable("CANNOT_REPORT", "You can report a problem once a Genie has accepted the booking");
        }
        if (now.isAfter(b.slotEnd().toInstant().plus(REPORT_WINDOW))) {
            throw ApiException.unprocessable("REPORT_WINDOW_CLOSED",
                "Problems can be reported up to 7 days after the booking. Please use Contact Us");
        }
        boolean hasOpen = jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_TICKET_OPEN_FOR_BOOKING))
            .param("b", bookingId).query(Boolean.class).single();
        if (hasOpen) {
            throw ApiException.conflict("TICKET_ALREADY_OPEN", "There is already an open report for this booking");
        }
        UUID id = insert("COMPLAINT", bookingId, userId, role, category, subject, description, now);
        Ticket t = load(id);
        events.publishEvent(new TicketRaised(t.id(), t.ticketRef(), bookingId, b.bookingRef(), userId, role,
            b.customerId(), b.genieId(), category.name(), category.priority(), subject));
        log.info("Ticket {} raised on {} ({}, {})", t.ticketRef(), b.bookingRef(), category, category.priority());
        return detail(id, userId, role);
    }

    /** A privacy request or grievance (DPDP), not tied to a booking. */
    @Transactional
    public TicketDetail raisePrivacy(UUID userId, String role, String subject, String description) {
        String s = required(subject, 150, "subject");
        String d = required(description, 2000, "description");
        UUID id = insert("PRIVACY", null, userId, role, TicketCategory.PRIVACY, s, d, clock.instant());
        Ticket t = load(id);
        events.publishEvent(new TicketRaised(t.id(), t.ticketRef(), null, null, userId, role, null, null,
            TicketCategory.PRIVACY.name(), TicketCategory.PRIVACY.priority(), s));
        return detail(id, userId, role);
    }

    private UUID insert(String type, UUID bookingId, UUID userId, String role, TicketCategory category, String subject,
                        String description, Instant now) {
        long seq = jdbc.sql(QueryUtil.get(QueryConstants.GET_TICKET_NEXT_REF)).query(Long.class).single();
        String ref = "PGT-%d-%06d".formatted(LocalDate.ofInstant(now, zone).getYear(), seq);
        try {
            return jdbc.sql(QueryUtil.get(QueryConstants.INSERT_TICKET_CREATE))
                .param("ref", ref).param("type", type).param("b", bookingId).param("u", userId).param("role", role)
                .param("cat", category.name()).param("prio", category.priority()).param("subject", subject)
                .param("desc", description).param("frd", Times.odt(now.plus(category.firstResponse)))
                .param("rd", Times.odt(now.plus(category.resolution)))
                .query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("TICKET_ALREADY_OPEN", "There is already an open report for this booking");
        }
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public PageResponse<TicketSummary> mine(UUID userId, String status, int page, int size) {
        String s = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        long total = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_TICKET_MINE))
            .param("u", userId).param("status", s).query(Long.class).single();
        List<TicketSummary> items = jdbc.sql(QueryUtil.get(QueryConstants.GET_TICKET_MINE))
            .param("u", userId).param("status", s)
            .param("limit", PageResponse.clampSize(size)).param("offset", PageResponse.offset(page, size))
            .query(TicketSummary.class).list();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    /** Detail for a party of the booking or an admin (admins also see internal notes). */
    @Transactional(readOnly = true)
    public TicketDetail detail(UUID ticketId, UUID viewerId, String viewerRole) {
        Ticket t = load(ticketId);
        boolean admin = "ADMIN".equals(viewerRole);
        if (!admin && !t.isParty(viewerId)) {
            throw ApiException.notFound("TICKET_NOT_FOUND", "Report not found");
        }
        record Row(UUID id, String ticketRef, String type, UUID bookingId, String bookingRef, String serviceName,
                   UUID customerId, String customerName, UUID genieId, String genieName, String category, String priority,
                   String status, String subject, String description, UUID raisedBy, String raisedByName,
                   String raisedByRole, UUID assignedTo, String assignedToName, OffsetDateTime firstResponseDue,
                   OffsetDateTime resolutionDue, boolean overdue, OffsetDateTime firstRespondedAt, OffsetDateTime resolvedAt,
                   OffsetDateTime closedAt, String resolutionActions, String resolutionNote, UUID refundId,
                   UUID redoBookingId, String redoBookingRef, OffsetDateTime createdAt) {
        }
        Row r = jdbc.sql(QueryUtil.get(QueryConstants.GET_TICKET_DETAIL))
            .param("id", ticketId).query(Row.class).single();
        List<TicketMessageDto> messages = jdbc.sql(QueryUtil.get(QueryConstants.GET_TICKET_MESSAGES))
            .param("id", ticketId).param("admin", admin).query(TicketMessageDto.class).list();
        List<AttachmentDto> attachments = attachments(ticketId);
        Instant now = clock.instant();
        boolean raiser = viewerId.equals(t.raisedBy());
        boolean reopenable = ("RESOLVED".equals(t.status()) || "REJECTED".equals(t.status())) && t.resolvedAt() != null
            && now.isBefore(t.resolvedAt().toInstant().plus(REOPEN_WINDOW));
        return new TicketDetail(r.id(), r.ticketRef(), r.type(), r.bookingId(), r.bookingRef(), r.serviceName(),
            r.customerId(), r.customerName(), r.genieId(), r.genieName(), r.category(), r.priority(), r.status(),
            r.subject(), r.description(), r.raisedBy(), r.raisedByName(), r.raisedByRole(), r.assignedTo(),
            r.assignedToName(), r.firstResponseDue(), r.resolutionDue(), r.overdue(), r.firstRespondedAt(),
            r.resolvedAt(), r.closedAt(), r.resolutionActions(), r.resolutionNote(), r.refundId(), r.redoBookingId(),
            r.redoBookingRef(), r.createdAt(), messages, attachments,
            t.isOpen(), t.isOpen() && attachments.size() < MAX_PHOTOS && (admin || t.isParty(viewerId)),
            raiser && reopenable && "RESOLVED".equals(t.status()), raiser && reopenable);
    }

    // ------------------------------------------------------------------ thread

    /** Customer or Genie adds a message. A reply to AWAITING_REPLY puts the ticket back in review. */
    @Transactional
    public TicketDetail reply(UUID ticketId, UUID userId, String role, String body) {
        Ticket t = lock(ticketId);
        if (!t.isParty(userId)) {
            throw ApiException.notFound("TICKET_NOT_FOUND", "Report not found");
        }
        if (!t.isOpen()) {
            throw ApiException.unprocessable("TICKET_CLOSED", "This report is " + t.status().toLowerCase(Locale.ROOT)
                + (canReopen(t, userId) ? "; reopen it to add a message" : ""));
        }
        String text = required(body, 2000, "message");
        addMessage(ticketId, userId, role, text, false);
        if ("AWAITING_REPLY".equals(t.status())) {
            setStatus(ticketId, "IN_REVIEW");
        } else {
            touch(ticketId);
        }
        events.publishEvent(new TicketReplied(t.id(), t.ticketRef(), t.bookingId(), t.bookingRef(), userId, role,
            t.raisedBy(), t.customerId(), t.genieId(), false, excerpt(text)));
        return detail(ticketId, userId, role);
    }

    /** Up to 5 photos per ticket (JPEG, PNG, WEBP; 5 MB each), checked by their bytes. */
    @Transactional
    public AttachmentDto addPhoto(UUID ticketId, UUID userId, String role, MultipartFile file) {
        Ticket t = lock(ticketId);
        if (!"ADMIN".equals(role) && !t.isParty(userId)) {
            throw ApiException.notFound("TICKET_NOT_FOUND", "Report not found");
        }
        if (!t.isOpen()) {
            throw ApiException.unprocessable("TICKET_CLOSED", "Photos can only be added while the report is open");
        }
        int count = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_TICKET_ATTACHMENTS)).param("t", ticketId)
            .query(Integer.class).single();
        if (count >= MAX_PHOTOS) {
            throw ApiException.unprocessable("TOO_MANY_PHOTOS", "A report can have at most " + MAX_PHOTOS + " photos");
        }
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("FILE_REQUIRED", "Choose a photo");
        }
        if (file.getSize() > MAX_PHOTO_BYTES) {
            throw ApiException.badRequest("FILE_TOO_LARGE", "Photos can be at most 5 MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String type = FileTypes.sniffImage(bytes);
        if (type == null) {
            throw ApiException.badRequest("UNSUPPORTED_FILE", "Only JPEG, PNG or WEBP photos are accepted");
        }
        UUID id = UUID.randomUUID();
        String key = "tickets/" + ticketId + "/" + id + "." + FileTypes.extension(type);
        storage.put(key, new ByteArrayInputStream(bytes), bytes.length, type);
        String name = file.getOriginalFilename() == null ? null
            : file.getOriginalFilename().replaceAll("[^A-Za-z0-9._ -]", "_");
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_TICKET_ATTACHMENT))
            .param("id", id).param("t", ticketId).param("u", userId).param("k", key)
            .param("n", name == null ? null : name.length() > 200 ? name.substring(0, 200) : name)
            .param("ct", type).param("size", bytes.length)
            .update();
        touch(ticketId);
        return attachments(ticketId).stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    public record FileContent(String fileName, String contentType, byte[] bytes) {
    }

    @Transactional(readOnly = true)
    public FileContent photo(UUID ticketId, UUID attachmentId, UUID userId, String role) {
        Ticket t = load(ticketId);
        if (!"ADMIN".equals(role) && !t.isParty(userId)) {
            throw ApiException.notFound("TICKET_NOT_FOUND", "Report not found");
        }
        record Att(String fileKey, String fileName, String contentType) {
        }
        Att a = jdbc.sql(QueryUtil.get(QueryConstants.GET_TICKET_ATTACHMENT))
            .param("id", attachmentId).param("t", ticketId).query(Att.class).optional()
            .orElseThrow(() -> ApiException.notFound("FILE_NOT_FOUND", "Photo not found"));
        try (InputStream in = storage.get(a.fileKey())) {
            return new FileContent(a.fileName(), a.contentType(), in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A short-lived link to a photo (presigned URL on S3), shown inline. Same access rule as {@link #photo}. */
    @Transactional(readOnly = true)
    public FileStorage.FileLink photoLink(UUID ticketId, UUID attachmentId, UUID userId, String role) {
        Ticket t = load(ticketId);
        if (!"ADMIN".equals(role) && !t.isParty(userId)) {
            throw ApiException.notFound("TICKET_NOT_FOUND", "Report not found");
        }
        record Att(String fileKey, String fileName, String contentType) {
        }
        Att a = jdbc.sql(QueryUtil.get(QueryConstants.GET_TICKET_ATTACHMENT))
            .param("id", attachmentId).param("t", ticketId).query(Att.class).optional()
            .orElseThrow(() -> ApiException.notFound("FILE_NOT_FOUND", "Photo not found"));
        return storage.link(a.fileKey(), a.fileName(), a.contentType(), true);
    }

    /** The person who raised it is happy with the resolution. */
    @Transactional
    public TicketDetail accept(UUID ticketId, UUID userId, String role) {
        Ticket t = lock(ticketId);
        if (!userId.equals(t.raisedBy())) {
            throw ApiException.notFound("TICKET_NOT_FOUND", "Report not found");
        }
        if (!"RESOLVED".equals(t.status())) {
            throw ApiException.unprocessable("INVALID_STATE", "Only a resolved report can be accepted");
        }
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_TICKET_ACCEPT))
            .param("id", ticketId).update();
        addMessage(ticketId, null, "SYSTEM", "The resolution was accepted and the report closed.", false);
        return detail(ticketId, userId, role);
    }

    /** Within 7 days of a resolution (or rejection), the person who raised it can reopen the report. */
    @Transactional
    public TicketDetail reopen(UUID ticketId, UUID userId, String role, String reason) {
        Ticket t = lock(ticketId);
        if (!userId.equals(t.raisedBy())) {
            throw ApiException.notFound("TICKET_NOT_FOUND", "Report not found");
        }
        if (!canReopen(t, userId)) {
            throw ApiException.unprocessable("CANNOT_REOPEN", "Reports can be reopened within 7 days of their resolution");
        }
        String text = required(reason, 2000, "reason");
        try {
            jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_TICKET_REOPEN))
                .param("id", ticketId).update();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("TICKET_ALREADY_OPEN", "There is already an open report for this booking");
        }
        addMessage(ticketId, userId, role, "Reopened: " + text, false);
        events.publishEvent(new TicketReplied(t.id(), t.ticketRef(), t.bookingId(), t.bookingRef(), userId, role,
            t.raisedBy(), t.customerId(), t.genieId(), false, excerpt("Reopened: " + text)));
        return detail(ticketId, userId, role);
    }

    // ------------------------------------------------------------------ helpers shared with TicketAdminService

    Ticket load(UUID ticketId) {
        return find(ticketId, QueryConstants.GET_TICKET_BY_ID);
    }

    Ticket lock(UUID ticketId) {
        return find(ticketId, QueryConstants.LOCK_TICKET_BY_ID);
    }

    /** {@code queryKey}: {@link QueryConstants#GET_TICKET_BY_ID} or {@link QueryConstants#LOCK_TICKET_BY_ID}. */
    private Ticket find(UUID ticketId, String queryKey) {
        return jdbc.sql(QueryUtil.get(queryKey))
            .param("id", ticketId).query(Ticket.class).optional()
            .orElseThrow(() -> ApiException.notFound("TICKET_NOT_FOUND", "Report not found"));
    }

    void addMessage(UUID ticketId, UUID authorId, String role, String body, boolean internal) {
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_TICKET_MESSAGE))
            .param("t", ticketId).param("a", authorId).param("r", role).param("b", body).param("i", internal)
            .update();
    }

    void setStatus(UUID ticketId, String status) {
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_TICKET_STATUS))
            .param("s", status).param("id", ticketId).update();
    }

    void touch(UUID ticketId) {
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_TICKET_TOUCH)).param("id", ticketId).update();
    }

    void publishResolved(Ticket t, String outcome, String actions, String note) {
        events.publishEvent(new TicketResolved(t.id(), t.ticketRef(), t.bookingId(), t.bookingRef(), t.raisedBy(),
            t.customerId(), t.genieId(), outcome, actions, note));
    }

    void publishReplied(Ticket t, UUID authorId, String role, boolean awaitingReply, String text) {
        events.publishEvent(new TicketReplied(t.id(), t.ticketRef(), t.bookingId(), t.bookingRef(), authorId, role,
            t.raisedBy(), t.customerId(), t.genieId(), awaitingReply, excerpt(text)));
    }

    List<AttachmentDto> attachments(UUID ticketId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_TICKET_ATTACHMENTS))
            .param("t", ticketId).query(AttachmentDto.class).list();
    }

    private boolean canReopen(Ticket t, UUID userId) {
        return userId.equals(t.raisedBy()) && ("RESOLVED".equals(t.status()) || "REJECTED".equals(t.status()))
            && t.resolvedAt() != null && clock.instant().isBefore(t.resolvedAt().toInstant().plus(REOPEN_WINDOW));
    }

    static TicketCategory parseCategory(String value) {
        try {
            return TicketCategory.valueOf(value == null ? "" : value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("INVALID_CATEGORY", "Pick what went wrong");
        }
    }

    static String required(String value, int max, String field) {
        if (!StringUtils.hasText(value)) {
            throw ApiException.badRequest("FIELD_REQUIRED", "Please fill in the " + field);
        }
        String v = value.trim();
        if (v.length() > max) {
            throw ApiException.badRequest("FIELD_TOO_LONG", "The " + field + " can be at most " + max + " characters");
        }
        return v;
    }

    static String excerpt(String text) {
        return text.length() <= 160 ? text : text.substring(0, 159) + "…";
    }
}
