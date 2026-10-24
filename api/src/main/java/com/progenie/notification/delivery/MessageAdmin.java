package com.progenie.notification.delivery;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.Masking;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Admin view of the outbox (failed deliveries, manual retry) and provider delivery reports. */
@Service
public class MessageAdmin {

    private static final Logger log = LoggerFactory.getLogger(MessageAdmin.class);
    private static final Set<String> STATUSES = Set.of("PENDING", "SENT", "FAILED", "SKIPPED");

    /** Destinations are masked; bodies of sensitive messages are already redacted. */
    public record MessageDto(UUID id, String channel, String template, String destination, String subject, String body,
                             String status, int attempts, String provider, String deliveryStatus, String lastError,
                             OffsetDateTime createdAt, OffsetDateTime sentAt, OffsetDateTime deliveredAt) {
    }

    private final JdbcClient jdbc;

    public MessageAdmin(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<MessageDto> list(String status, String channel, String q, int page, int size) {
        String s = status == null || status.isBlank() ? null : status.toUpperCase(Locale.ROOT);
        if (s != null && !STATUSES.contains(s)) {
            throw ApiException.badRequest("BAD_STATUS", "Unknown status " + status);
        }
        String ch = channel == null || channel.isBlank() ? null : channel.toUpperCase(Locale.ROOT);
        if (ch != null) {
            try {
                Channel.valueOf(ch);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("BAD_CHANNEL", "Unknown channel " + channel);
            }
        }
        String search = q == null || q.isBlank() ? null : "%" + q.trim() + "%";
        long total = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_MESSAGE_LIST))
            .param("status", s).param("channel", ch).param("q", search)
            .query(Long.class).single();
        List<MessageDto> items = jdbc.sql(QueryUtil.get(QueryConstants.GET_MESSAGE_LIST))
            .param("status", s).param("channel", ch).param("q", search)
            .param("limit", PageResponse.clampSize(size)).param("offset", PageResponse.offset(page, size))
            .query(MessageDto.class).list().stream()
            .map(m -> new MessageDto(m.id(), m.channel(), m.template(), Masking.destination(m.destination()), m.subject(),
                m.body(), m.status(), m.attempts(), m.provider(), m.deliveryStatus(), m.lastError(), m.createdAt(),
                m.sentAt(), m.deliveredAt()))
            .toList();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    /** Puts a FAILED message back in the queue with a fresh retry budget. */
    @Transactional
    public void retry(UUID id) {
        String status = jdbc.sql(QueryUtil.get(QueryConstants.LOCK_MESSAGE_STATUS))
            .param("id", id).query(String.class).optional()
            .orElseThrow(() -> ApiException.notFound("MESSAGE_NOT_FOUND", "Message not found"));
        if (!"FAILED".equals(status)) {
            throw ApiException.unprocessable("NOT_FAILED", "Only failed messages can be retried");
        }
        if ("[redacted]".equals(jdbc.sql(QueryUtil.get(QueryConstants.GET_MESSAGE_BODY)).param("id", id)
                .query(String.class).single())) {
            throw ApiException.unprocessable("MESSAGE_EXPIRED", "One-time codes can't be resent; ask the user to request a new one");
        }
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_MESSAGE_RETRY))
            .param("id", id).update();
    }

    /** Applies a provider delivery report (e.g. MSG91 DELIVERED / FAILED) to the matching row. */
    @Transactional
    public boolean applyDeliveryReport(String provider, String providerMessageId, String deliveryStatus) {
        if (providerMessageId == null || deliveryStatus == null) {
            return false;
        }
        String s = deliveryStatus.trim().toUpperCase(Locale.ROOT);
        boolean delivered = s.startsWith("DELIVER") || s.equals("1") || s.equals("READ");
        int rows = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_MESSAGE_DELIVERY_REPORT))
            .param("s", delivered ? "DELIVERED" : MessageOutbox.truncate(s, 20)).param("delivered", delivered)
            .param("p", provider).param("pid", providerMessageId)
            .update();
        log.debug("Delivery report {} {} → {} ({} row)", provider, providerMessageId, s, rows);
        return rows > 0;
    }
}
