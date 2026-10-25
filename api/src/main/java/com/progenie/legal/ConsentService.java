package com.progenie.legal;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Who accepted which policy version. Customers need the Terms and Privacy Policy; Genies also the Partner
 * Agreement; admins none. A consent is pending when the user never accepted that kind, or when a newer
 * published version marked "requires re-acceptance" hasn't been accepted.
 */
@Service
public class ConsentService {

    public record ConsentDto(LegalKind kind, int version, String title, OffsetDateTime acceptedAt) {
    }

    private final JdbcClient jdbc;

    public ConsentService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public static List<LegalKind> requiredKinds(String role) {
        return switch (role) {
            case "CUSTOMER" -> List.of(LegalKind.TERMS, LegalKind.PRIVACY);
            case "GENIE" -> List.of(LegalKind.TERMS, LegalKind.PRIVACY, LegalKind.GENIE_AGREEMENT);
            default -> List.of();
        };
    }

    @Transactional(readOnly = true)
    public List<String> pendingKinds(UUID userId, String role) {
        List<String> pending = new ArrayList<>();
        for (LegalKind kind : requiredKinds(role)) {
            Boolean isPending = jdbc.sql(QueryUtil.get(QueryConstants.GET_CONSENT_IS_PENDING))
                .param("u", userId).param("k", kind.name())
                .query(Boolean.class).single();
            if (Boolean.TRUE.equals(isPending)) {
                pending.add(kind.name());
            }
        }
        return pending;
    }

    /** 428 CONSENT_REQUIRED (with {@code pendingConsents}) until the user accepts the updated policies. */
    @Transactional(readOnly = true)
    public void requireNoPending(UUID userId, String role) {
        List<String> pending = pendingKinds(userId, role);
        if (!pending.isEmpty()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "CONSENT_REQUIRED",
                "Please review and accept the updated policies to continue").with("pendingConsents", pending);
        }
    }

    /** Sign-up: accepts the current version of every policy the role needs. */
    @Transactional
    public void acceptCurrent(UUID userId, String role, String ip, String userAgent) {
        accept(userId, role, requiredKinds(role), ip, userAgent);
    }

    /** Accepts the current published version of the given kinds (only kinds the role needs). */
    @Transactional
    public List<String> accept(UUID userId, String role, Collection<LegalKind> kinds, String ip, String userAgent) {
        List<LegalKind> required = requiredKinds(role);
        for (LegalKind kind : kinds) {
            if (!required.contains(kind)) {
                throw ApiException.badRequest("CONSENT_NOT_NEEDED", kind.name() + " doesn't apply to your account");
            }
            jdbc.sql(QueryUtil.get(QueryConstants.INSERT_CONSENT_ACCEPT))
                .param("u", userId).param("k", kind.name())
                .param("ip", ip == null ? null : ip.length() > 45 ? ip.substring(0, 45) : ip)
                .param("ua", userAgent == null ? null : userAgent.length() > 300 ? userAgent.substring(0, 300) : userAgent)
                .update();
        }
        return pendingKinds(userId, role);
    }

    /** The user's acceptance history (profile page and data export). */
    @Transactional(readOnly = true)
    public List<ConsentDto> history(UUID userId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_CONSENT_HISTORY))
            .param("u", userId).query(ConsentDto.class).list();
    }
}
