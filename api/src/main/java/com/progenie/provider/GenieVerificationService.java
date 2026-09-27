package com.progenie.provider;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.progenie.provider.GenieEvents.GenieVerificationDecided;
import com.progenie.provider.GenieProfileDtos.MyProfileDto;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.web.PageResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Admin verification of Genies. State machine:
 * REGISTERED → UNDER_REVIEW → APPROVED | NEEDS_CHANGES | REJECTED; APPROVED ⇄ SUSPENDED.
 * Every change is recorded in genie_verification_events.
 */
@Service
public class GenieVerificationService {

    public enum Decision { APPROVE, NEEDS_CHANGES, REJECT, SUSPEND, REINSTATE }

    /** Allowed source states and the resulting state for each decision. */
    private static final Map<Decision, Set<String>> FROM = Map.of(
        Decision.APPROVE, Set.of("UNDER_REVIEW"),
        Decision.NEEDS_CHANGES, Set.of("UNDER_REVIEW"),
        Decision.REJECT, Set.of("UNDER_REVIEW", "NEEDS_CHANGES", "REGISTERED"),
        Decision.SUSPEND, Set.of("APPROVED"),
        Decision.REINSTATE, Set.of("SUSPENDED"));
    private static final Map<Decision, String> TO = Map.of(
        Decision.APPROVE, "APPROVED", Decision.NEEDS_CHANGES, "NEEDS_CHANGES", Decision.REJECT, "REJECTED",
        Decision.SUSPEND, "SUSPENDED", Decision.REINSTATE, "APPROVED");

    public record QueueItemDto(UUID id, String fullName, String phone, String baseArea, String verificationStatus,
                               OffsetDateTime submittedAt, OffsetDateTime createdAt, int serviceCount,
                               int pendingDocuments, OffsetDateTime flaggedAt, String flagReason) {
    }

    public record VerificationEventDto(String fromStatus, String toStatus, String reason, String actorName,
                                       OffsetDateTime createdAt) {
    }

    public record AdminGenieDetailDto(MyProfileDto profile, String userStatus, OffsetDateTime flaggedAt,
                                      String flagReason, List<VerificationEventDto> history) {
    }

    private final JdbcClient jdbc;
    private final GenieProfileService profiles;
    private final ApplicationEventPublisher events;

    public GenieVerificationService(JdbcClient jdbc, GenieProfileService profiles, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public PageResponse<QueueItemDto> queue(String status, Boolean flagged, String q, int page, int size) {
        String where = """
             WHERE (CAST(:status AS varchar) IS NULL OR gp.verification_status = :status)
               AND (CAST(:flagged AS boolean) IS NULL OR (gp.flagged_at IS NOT NULL) = :flagged)
               AND (CAST(:q AS varchar) IS NULL OR u.full_name ILIKE :q OR u.phone LIKE :q)
            """;
        String query = StringUtils.hasText(q) ? "%" + q.trim() + "%" : null;
        String statusFilter = StringUtils.hasText(status) ? status.trim().toUpperCase() : null;
        long total = jdbc.sql("SELECT count(*) FROM genie_profiles gp JOIN users u ON u.id = gp.user_id " + where)
            .param("status", statusFilter).param("flagged", flagged).param("q", query)
            .query(Long.class).single();
        List<QueueItemDto> items = jdbc.sql("""
                SELECT u.id, u.full_name, u.phone, gp.base_area, gp.verification_status, gp.submitted_at, gp.created_at,
                       (SELECT count(*) FROM genie_services gs WHERE gs.genie_id = gp.user_id AND gs.is_active) AS service_count,
                       (SELECT count(*) FROM genie_documents d WHERE d.genie_id = gp.user_id AND d.status = 'PENDING')
                           AS pending_documents,
                       gp.flagged_at, gp.flag_reason
                  FROM genie_profiles gp JOIN users u ON u.id = gp.user_id
                """ + where + """
                 ORDER BY gp.flagged_at DESC NULLS LAST, gp.submitted_at ASC NULLS LAST, gp.created_at DESC
                 LIMIT :limit OFFSET :offset
                """)
            .param("status", statusFilter).param("flagged", flagged).param("q", query)
            .param("limit", PageResponse.clampSize(size)).param("offset", PageResponse.offset(page, size))
            .query(QueueItemDto.class)
            .list();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    @Transactional(readOnly = true)
    public AdminGenieDetailDto detail(UUID genieId) {
        MyProfileDto profile = profiles.me(genieId);
        record Extra(String userStatus, OffsetDateTime flaggedAt, String flagReason) {
        }
        Extra extra = jdbc.sql("""
                SELECT u.status AS user_status, gp.flagged_at, gp.flag_reason
                  FROM genie_profiles gp JOIN users u ON u.id = gp.user_id WHERE gp.user_id = :id
                """)
            .param("id", genieId).query(Extra.class).single();
        List<VerificationEventDto> history = jdbc.sql("""
                SELECT e.from_status, e.to_status, e.reason, a.full_name AS actor_name, e.created_at
                  FROM genie_verification_events e LEFT JOIN users a ON a.id = e.actor_id
                 WHERE e.genie_id = :id ORDER BY e.created_at DESC, e.id DESC
                """)
            .param("id", genieId).query(VerificationEventDto.class).list();
        return new AdminGenieDetailDto(profile, extra.userStatus(), extra.flaggedAt(), extra.flagReason(), history);
    }

    @Transactional
    public AdminGenieDetailDto decide(UUID genieId, UUID adminId, Decision decision, String note) {
        String from = jdbc.sql("SELECT verification_status FROM genie_profiles WHERE user_id = :id FOR UPDATE")
            .param("id", genieId).query(String.class).optional()
            .orElseThrow(() -> ApiException.notFound("GENIE_NOT_FOUND", "Genie not found"));
        if (!FROM.get(decision).contains(from)) {
            throw ApiException.unprocessable("INVALID_TRANSITION", "Cannot " + decision + " a Genie who is " + from);
        }
        boolean noteRequired = decision != Decision.APPROVE && decision != Decision.REINSTATE;
        if (noteRequired && !StringUtils.hasText(note)) {
            throw ApiException.badRequest("NOTE_REQUIRED", "Please tell the Genie why");
        }
        String to = TO.get(decision);
        if (decision == Decision.APPROVE) {
            // Approving the Genie accepts every document still pending review.
            jdbc.sql("""
                    UPDATE genie_documents SET status = 'APPROVED', reviewed_by = :admin, reviewed_at = now()
                     WHERE genie_id = :id AND status = 'PENDING'
                    """)
                .param("admin", adminId).param("id", genieId).update();
        }
        jdbc.sql("""
                UPDATE genie_profiles
                   SET verification_status = :to,
                       verification_note = :note,
                       is_online = CASE WHEN :to = 'APPROVED' THEN is_online ELSE FALSE END,
                       approved_at = CASE WHEN :decision = 'APPROVE' THEN now() ELSE approved_at END,
                       approved_by = CASE WHEN :decision = 'APPROVE' THEN CAST(:admin AS uuid) ELSE approved_by END,
                       flagged_at = CASE WHEN :decision = 'REINSTATE' THEN NULL ELSE flagged_at END,
                       flag_reason = CASE WHEN :decision = 'REINSTATE' THEN NULL ELSE flag_reason END,
                       updated_at = now()
                 WHERE user_id = :id
                """)
            .param("to", to)
            .param("note", StringUtils.hasText(note) ? note.trim() : null)
            .param("decision", decision.name())
            .param("admin", adminId)
            .param("id", genieId)
            .update();
        jdbc.sql("""
                INSERT INTO genie_verification_events (genie_id, from_status, to_status, reason, actor_id)
                VALUES (:id, :from, :to, :reason, :admin)
                """)
            .param("id", genieId).param("from", from).param("to", to)
            .param("reason", StringUtils.hasText(note) ? note.trim() : decision.name())
            .param("admin", adminId)
            .update();
        events.publishEvent(new GenieVerificationDecided(genieId, from, to, note));
        return detail(genieId);
    }

    @Transactional
    public void reviewDocument(UUID docId, UUID adminId, boolean approve, String note) {
        if (!approve && !StringUtils.hasText(note)) {
            throw ApiException.badRequest("NOTE_REQUIRED", "Please say why the document was rejected");
        }
        int rows = jdbc.sql("""
                UPDATE genie_documents SET status = :status, review_note = :note, reviewed_by = :admin, reviewed_at = now()
                 WHERE id = :id
                """)
            .param("status", approve ? "APPROVED" : "REJECTED")
            .param("note", StringUtils.hasText(note) ? note.trim() : null)
            .param("admin", adminId)
            .param("id", docId)
            .update();
        if (rows == 0) {
            throw ApiException.notFound("DOCUMENT_NOT_FOUND", "Document not found");
        }
    }

    @Transactional
    public void clearFlag(UUID genieId, UUID adminId) {
        int rows = jdbc.sql("UPDATE genie_profiles SET flagged_at = NULL, flag_reason = NULL WHERE user_id = :id")
            .param("id", genieId).update();
        if (rows == 0) {
            throw ApiException.notFound("GENIE_NOT_FOUND", "Genie not found");
        }
        String status = jdbc.sql("SELECT verification_status FROM genie_profiles WHERE user_id = :id")
            .param("id", genieId).query(String.class).single();
        jdbc.sql("""
                INSERT INTO genie_verification_events (genie_id, from_status, to_status, reason, actor_id)
                VALUES (:id, :s, :s, 'Reliability flag cleared', :admin)
                """)
            .param("id", genieId).param("s", status).param("admin", adminId).update();
    }
}
