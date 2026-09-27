package com.progenie.support;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

import com.progenie.shared.error.ApiException;
import com.progenie.shared.web.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Admin inbox for "Contact us" messages. */
@Service
public class SupportInboxService {

    private static final Set<String> STATUSES = Set.of("NEW", "IN_PROGRESS", "RESOLVED");

    public record ContactMessageDto(long id, String name, String email, String phone, String subject, String message,
                                    String status, OffsetDateTime createdAt, OffsetDateTime resolvedAt) {
    }

    private final JdbcClient jdbc;

    public SupportInboxService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<ContactMessageDto> list(String status, int page, int size) {
        String statusFilter = StringUtils.hasText(status) ? status.trim().toUpperCase() : null;
        String where = " WHERE (CAST(:status AS varchar) IS NULL OR status = :status)";
        long total = jdbc.sql("SELECT count(*) FROM contact_messages" + where).param("status", statusFilter)
            .query(Long.class).single();
        List<ContactMessageDto> items = jdbc.sql("""
                SELECT id, name, email, phone, subject, message, status, created_at, resolved_at FROM contact_messages
                """ + where + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
            .param("status", statusFilter)
            .param("limit", PageResponse.clampSize(size)).param("offset", PageResponse.offset(page, size))
            .query(ContactMessageDto.class).list();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    @Transactional
    public ContactMessageDto setStatus(long id, String status) {
        String s = status == null ? "" : status.trim().toUpperCase();
        if (!STATUSES.contains(s)) {
            throw ApiException.badRequest("INVALID_STATUS", "Status must be one of " + STATUSES);
        }
        return jdbc.sql("""
                UPDATE contact_messages
                   SET status = :status, resolved_at = CASE WHEN :status = 'RESOLVED' THEN now() ELSE NULL END
                 WHERE id = :id
                RETURNING id, name, email, phone, subject, message, status, created_at, resolved_at
                """)
            .param("status", s).param("id", id)
            .query(ContactMessageDto.class).optional()
            .orElseThrow(() -> ApiException.notFound("MESSAGE_NOT_FOUND", "Message not found"));
    }
}
