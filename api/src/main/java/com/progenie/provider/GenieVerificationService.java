package com.progenie.provider;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.progenie.provider.GenieEvents.GenieVerificationDecided;
import com.progenie.provider.GenieProfileDtos.MyProfileDto;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.jspecify.annotations.Nullable;
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

    private static final Logger log = LoggerFactory.getLogger(GenieVerificationService.class);

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

    public record QueueItemDto(UUID id, String fullName, String phone, @Nullable String baseArea, String verificationStatus,
                               @Nullable OffsetDateTime submittedAt, OffsetDateTime createdAt, int serviceCount,
                               int pendingDocuments, @Nullable OffsetDateTime flaggedAt, @Nullable String flagReason) {
    }

    public record VerificationEventDto(@Nullable String fromStatus, String toStatus, @Nullable String reason, @Nullable String actorName,
                                       OffsetDateTime createdAt) {
    }

    public record AdminGenieDetailDto(MyProfileDto profile, String userStatus, @Nullable OffsetDateTime flaggedAt,
                                      @Nullable String flagReason, List<VerificationEventDto> history) {
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
        String query = StringUtils.hasText(q) ? "%" + q.trim() + "%" : null;
        String statusFilter = StringUtils.hasText(status) ? status.trim().toUpperCase() : null;
        long total = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_GENIE_VERIFICATION_QUEUE))
            .param("status", statusFilter).param("flagged", flagged).param("q", query)
            .query(Long.class).single();
        List<QueueItemDto> items = jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_VERIFICATION_QUEUE))
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
        Extra extra = jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_VERIFICATION_FLAGS))
            .param("id", genieId).query(Extra.class).single();
        List<VerificationEventDto> history = jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_VERIFICATION_EVENTS))
            .param("id", genieId).query(VerificationEventDto.class).list();
        return new AdminGenieDetailDto(profile, extra.userStatus(), extra.flaggedAt(), extra.flagReason(), history);
    }

    @Transactional
    @GenieCaches.EvictsGenies
    public AdminGenieDetailDto decide(UUID genieId, UUID adminId, Decision decision, String note) {
        String from = jdbc.sql(QueryUtil.get(QueryConstants.LOCK_GENIE_PROFILE_STATUS))
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
            jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_GENIE_DOCUMENT_APPROVE_PENDING))
                .param("admin", adminId).param("id", genieId).update();
        }
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_GENIE_PROFILE_DECISION))
            .param("to", to)
            .param("note", StringUtils.hasText(note) ? note.trim() : null)
            .param("decision", decision.name())
            .param("admin", adminId)
            .param("id", genieId)
            .update();
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_GENIE_VERIFICATION_EVENT))
            .param("id", genieId).param("from", from).param("to", to)
            .param("reason", StringUtils.hasText(note) ? note.trim() : decision.name())
            .param("admin", adminId)
            .update();
        log.info("Genie {}: {} -> {} ({} by admin {})", genieId, from, to, decision, adminId);
        events.publishEvent(new GenieVerificationDecided(genieId, from, to, note));
        return detail(genieId);
    }

    @Transactional
    public void reviewDocument(UUID docId, UUID adminId, boolean approve, String note) {
        if (!approve && !StringUtils.hasText(note)) {
            throw ApiException.badRequest("NOTE_REQUIRED", "Please say why the document was rejected");
        }
        int rows = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_GENIE_DOCUMENT_REVIEW))
            .param("status", approve ? "APPROVED" : "REJECTED")
            .param("note", StringUtils.hasText(note) ? note.trim() : null)
            .param("admin", adminId)
            .param("id", docId)
            .update();
        if (rows == 0) {
            throw ApiException.notFound("DOCUMENT_NOT_FOUND", "Document not found");
        }
        log.info("KYC document {} {} by admin {}", docId, approve ? "approved" : "rejected", adminId);
    }

    @Transactional
    @GenieCaches.EvictsGenies
    public void clearFlag(UUID genieId, UUID adminId) {
        int rows = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_GENIE_PROFILE_CLEAR_FLAG))
            .param("id", genieId).update();
        if (rows == 0) {
            throw ApiException.notFound("GENIE_NOT_FOUND", "Genie not found");
        }
        String status = jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_PROFILE_STATUS))
            .param("id", genieId).query(String.class).single();
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_GENIE_VERIFICATION_FLAG_CLEARED))
            .param("id", genieId).param("s", status).param("admin", adminId).update();
    }
}
