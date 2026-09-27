package com.progenie.support;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.progenie.booking.BookingReworks;
import com.progenie.payment.RefundService;
import com.progenie.payment.RefundService.RefundDto;
import com.progenie.payment.RefundService.RefundRequest;
import com.progenie.provider.GenieStrikes;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.web.PageResponse;
import com.progenie.support.TicketService.Ticket;
import com.progenie.support.TicketService.TicketDetail;
import com.progenie.support.TicketService.TicketSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The admin side of complaints: the queue with deadlines, assignment, replies and internal notes, and the
 * resolutions (refund, free redo, Genie strike, no action), which can be combined and are all recorded.
 */
@Service
public class TicketAdminService {

    private static final Logger log = LoggerFactory.getLogger(TicketAdminService.class);
    private static final Set<String> ACTIONS = Set.of("REFUND", "REDO", "STRIKE", "NO_ACTION");

    /** @param awaitingReply true = the ticket waits for the customer/Genie to answer */
    public record AdminReply(String body, Boolean internal, Boolean awaitingReply) {

        boolean isInternal() {
            return Boolean.TRUE.equals(internal);
        }

        boolean isAwaitingReply() {
            return Boolean.TRUE.equals(awaitingReply);
        }
    }

    /** Redo: when (and optionally another Genie). */
    public record RedoRequest(OffsetDateTime slotStart, UUID genieId) {
    }

    /**
     * @param actions      any of REFUND, REDO, STRIKE; or NO_ACTION alone
     * @param note         shown to the customer (and the Genie)
     * @param refund       for REFUND; paymentId may be left out (the booking's payment is used)
     * @param strikeReason for STRIKE; defaults to the note
     */
    public record ResolveRequest(List<String> actions, String note, RefundRequest refund, UUID paymentId,
                                 RedoRequest redo, String strikeReason) {
    }

    private final JdbcClient jdbc;
    private final TicketService tickets;
    private final RefundService refunds;
    private final BookingReworks reworks;
    private final GenieStrikes strikes;
    private final JobLock lock;

    public TicketAdminService(JdbcClient jdbc, TicketService tickets, RefundService refunds, BookingReworks reworks,
                              GenieStrikes strikes, JobLock lock) {
        this.jdbc = jdbc;
        this.tickets = tickets;
        this.refunds = refunds;
        this.reworks = reworks;
        this.strikes = strikes;
        this.lock = lock;
    }

    /**
     * The queue. {@code status}: a status, ACTIVE (all open ones, the default) or ALL. Overdue tickets first,
     * then by priority and deadline.
     */
    @Transactional(readOnly = true)
    public PageResponse<TicketSummary> queue(String status, String priority, String type, boolean overdueOnly,
                                             boolean mine, UUID adminId, String q, int page, int size) {
        String s = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "ACTIVE";
        String p = StringUtils.hasText(priority) ? priority.trim().toUpperCase(Locale.ROOT) : null;
        String ty = StringUtils.hasText(type) ? type.trim().toUpperCase(Locale.ROOT) : null;
        String query = StringUtils.hasText(q) ? "%" + q.trim() + "%" : null;
        String where = """
             WHERE (:status = 'ALL' OR t.status = :status
                    OR (:status = 'ACTIVE' AND t.status IN ('OPEN', 'IN_REVIEW', 'AWAITING_REPLY')))
               AND (CAST(:priority AS varchar) IS NULL OR t.priority = :priority)
               AND (CAST(:type AS varchar) IS NULL OR t.type = :type)
               AND (NOT :mine OR t.assigned_to = :admin)
               AND (CAST(:q AS varchar) IS NULL OR t.ticket_ref ILIKE :q OR b.booking_ref ILIKE :q OR t.subject ILIKE :q
                    OR ru.full_name ILIKE :q)
            """ + (overdueOnly ? " AND " + TicketService.OVERDUE_SQL : "");
        long total = jdbc.sql("""
                SELECT count(*) FROM tickets t LEFT JOIN bookings b ON b.id = t.booking_id JOIN users ru ON ru.id = t.raised_by
                """ + where)
            .param("status", s).param("priority", p).param("type", ty).param("mine", mine).param("admin", adminId)
            .param("q", query).query(Long.class).single();
        List<TicketSummary> items = jdbc.sql(TicketService.SUMMARY_SELECT + where + """
                 ORDER BY overdue DESC, CASE t.priority WHEN 'URGENT' THEN 0 WHEN 'HIGH' THEN 1 ELSE 2 END,
                          t.resolution_due, t.created_at
                 LIMIT :limit OFFSET :offset
                """)
            .param("status", s).param("priority", p).param("type", ty).param("mine", mine).param("admin", adminId)
            .param("q", query)
            .param("limit", PageResponse.clampSize(size)).param("offset", PageResponse.offset(page, size))
            .query(TicketSummary.class).list();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    /** Takes the ticket (or hands it to another admin); an OPEN ticket moves to IN_REVIEW. */
    @Transactional
    public TicketDetail assign(UUID ticketId, UUID adminId, UUID assignee) {
        Ticket t = tickets.lock(ticketId);
        requireOpen(t);
        UUID to = assignee == null ? adminId : assignee;
        boolean isAdmin = jdbc.sql("SELECT EXISTS (SELECT 1 FROM users WHERE id = :id AND role = 'ADMIN' AND status = 'ACTIVE')")
            .param("id", to).query(Boolean.class).single();
        if (!isAdmin) {
            throw ApiException.badRequest("NOT_AN_ADMIN", "Tickets can only be assigned to an admin");
        }
        jdbc.sql("""
                UPDATE tickets SET assigned_to = :a, status = CASE WHEN status = 'OPEN' THEN 'IN_REVIEW' ELSE status END,
                       updated_at = now(), version = version + 1
                 WHERE id = :id
                """)
            .param("a", to).param("id", ticketId).update();
        return tickets.detail(ticketId, adminId, "ADMIN");
    }

    /** A reply both sides see, or an internal note. The first visible reply stops the first-response clock. */
    @Transactional
    public TicketDetail reply(UUID ticketId, UUID adminId, AdminReply req) {
        Ticket t = tickets.lock(ticketId);
        requireOpen(t);
        String text = TicketService.required(req.body(), 2000, "message");
        tickets.addMessage(ticketId, adminId, "ADMIN", text, req.isInternal());
        if (!req.isInternal()) {
            jdbc.sql("""
                    UPDATE tickets
                       SET first_responded_at = coalesce(first_responded_at, now()),
                           assigned_to = coalesce(assigned_to, :admin),
                           status = :status, updated_at = now(), version = version + 1
                     WHERE id = :id
                    """)
                .param("admin", adminId).param("status", req.isAwaitingReply() ? "AWAITING_REPLY" : "IN_REVIEW")
                .param("id", ticketId).update();
            tickets.publishReplied(t, adminId, "ADMIN", req.isAwaitingReply(), text);
        } else {
            tickets.touch(ticketId);
        }
        return tickets.detail(ticketId, adminId, "ADMIN");
    }

    /** Applies the chosen resolutions in one transaction: if any fails (e.g. the redo slot is taken), none apply. */
    @Transactional
    public TicketDetail resolve(UUID ticketId, UUID adminId, ResolveRequest req) {
        Ticket t = tickets.lock(ticketId);
        requireOpen(t);
        Set<String> actions = new LinkedHashSet<>();
        for (String a : req.actions() == null ? List.<String>of() : req.actions()) {
            String up = a == null ? "" : a.trim().toUpperCase(Locale.ROOT);
            if (!ACTIONS.contains(up)) {
                throw ApiException.badRequest("INVALID_ACTION", "Unknown action " + a);
            }
            actions.add(up);
        }
        if (actions.isEmpty()) {
            throw ApiException.badRequest("ACTION_REQUIRED", "Choose at least one resolution");
        }
        if (actions.contains("NO_ACTION") && actions.size() > 1) {
            throw ApiException.badRequest("INVALID_ACTION", "No action can't be combined with other resolutions");
        }
        String note = TicketService.required(req.note(), 1000, "resolution note");
        if (t.bookingId() == null && !actions.equals(Set.of("NO_ACTION"))) {
            throw ApiException.unprocessable("INVALID_ACTION", "Privacy requests are resolved without booking actions");
        }

        UUID refundId = null;
        UUID redoId = null;
        List<String> summary = new ArrayList<>();
        if (actions.contains("REFUND")) {
            UUID paymentId = req.paymentId() != null ? req.paymentId() : bookingPayment(t.bookingId());
            RefundRequest r = req.refund() == null
                ? new RefundRequest(null, "GENIE", null, null, null, null) : req.refund();
            String reason = StringUtils.hasText(r.reason()) ? r.reason() : "Complaint " + t.ticketRef();
            RefundDto refund = refunds.create(adminId, paymentId, new RefundRequest(r.amount(), r.liability(),
                r.genieShare(), reason, r.method(), r.manualReference()), ticketId);
            refundId = refund.id();
            summary.add("Refund of ₹" + refund.amount() + ("PROCESSED".equals(refund.status()) ? "" : " (processing)"));
        }
        if (actions.contains("REDO")) {
            if (req.redo() == null || req.redo().slotStart() == null) {
                throw ApiException.badRequest("SLOT_REQUIRED", "Pick a time for the free redo");
            }
            redoId = reworks.create(t.bookingId(), req.redo().genieId(), req.redo().slotStart().toInstant(), adminId);
            summary.add("Free redo booked");
        }
        if (actions.contains("STRIKE")) {
            if (t.genieId() == null) {
                throw ApiException.unprocessable("INVALID_ACTION", "This report has no Genie to warn");
            }
            String reason = StringUtils.hasText(req.strikeReason()) ? req.strikeReason().trim() : note;
            int recent = strikes.add(t.genieId(), ticketId, reason.length() > 300 ? reason.substring(0, 300) : reason, adminId);
            summary.add("Genie warned (" + recent + " in 90 days)");
        }
        String actionList = String.join(",", actions);
        jdbc.sql("""
                UPDATE tickets
                   SET status = 'RESOLVED', resolved_at = now(), resolution_actions = :actions, resolution_note = :note,
                       refund_id = coalesce(:refund, refund_id), redo_booking_id = coalesce(:redo, redo_booking_id),
                       first_responded_at = coalesce(first_responded_at, now()), assigned_to = coalesce(assigned_to, :admin),
                       updated_at = now(), version = version + 1
                 WHERE id = :id
                """)
            .param("actions", actionList).param("note", note).param("refund", refundId).param("redo", redoId)
            .param("admin", adminId).param("id", ticketId).update();
        String message = "Resolved: " + note + (summary.isEmpty() ? "" : " (" + String.join("; ", summary) + ")");
        tickets.addMessage(ticketId, adminId, "ADMIN", message, false);
        tickets.publishResolved(t, "RESOLVED", actionList, note);
        log.info("Ticket {} resolved by {}: {}", t.ticketRef(), adminId, actionList);
        return tickets.detail(ticketId, adminId, "ADMIN");
    }

    /** Not a valid complaint (reason shown to the customer). They can still reopen it within 7 days. */
    @Transactional
    public TicketDetail reject(UUID ticketId, UUID adminId, String reason) {
        Ticket t = tickets.lock(ticketId);
        requireOpen(t);
        String note = TicketService.required(reason, 1000, "reason");
        jdbc.sql("""
                UPDATE tickets SET status = 'REJECTED', resolved_at = now(), resolution_note = :note,
                       resolution_actions = 'REJECTED', first_responded_at = coalesce(first_responded_at, now()),
                       assigned_to = coalesce(assigned_to, :admin), updated_at = now(), version = version + 1
                 WHERE id = :id
                """)
            .param("note", note).param("admin", adminId).param("id", ticketId).update();
        tickets.addMessage(ticketId, adminId, "ADMIN", "Closed without action: " + note, false);
        tickets.publishResolved(t, "REJECTED", "REJECTED", note);
        return tickets.detail(ticketId, adminId, "ADMIN");
    }

    /** Resolved or rejected tickets close by themselves after 7 days. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT2M")
    @Transactional
    public void closeSettledTickets() {
        if (!lock.tryAcquire("ticket-auto-close")) {
            return;
        }
        int rows = jdbc.sql("""
                UPDATE tickets SET status = 'CLOSED', closed_at = now(), updated_at = now(), version = version + 1
                 WHERE status IN ('RESOLVED', 'REJECTED') AND resolved_at < now() - interval '7 days'
                """).update();
        if (rows > 0) {
            log.info("Auto-closed {} settled tickets", rows);
        }
    }

    private UUID bookingPayment(UUID bookingId) {
        return jdbc.sql("""
                SELECT id FROM payments WHERE booking_id = :b AND status = 'SUCCEEDED'
                 ORDER BY CASE purpose WHEN 'BOOKING' THEN 0 ELSE 1 END, created_at DESC LIMIT 1
                """)
            .param("b", bookingId).query(UUID.class).optional()
            .orElseThrow(() -> ApiException.unprocessable("NOTHING_TO_REFUND", "This booking has no payment to refund"));
    }

    private static void requireOpen(Ticket t) {
        if (!t.isOpen()) {
            throw ApiException.unprocessable("TICKET_CLOSED", "This report is already " + t.status().toLowerCase(Locale.ROOT));
        }
    }
}
