package com.progenie.messaging.delivery;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.util.QueryUtil;

/** SQL for the {@code outbound_messages} table (the transactional outbox). */
@Component
class MessageOutbox {

    /** A row ready to hand to a sender. */
    record QueuedMessage(UUID id, Channel channel, String template, String destination, String subject, String body,
                         Map<String, String> params, String attachmentKey, String attachmentName, int attempts) {
    }

    /** What {@link Messenger} writes. */
    record NewMessage(UUID userId, Channel channel, MessageTemplate template, String destination, String subject,
                      String body, Map<String, String> params, String attachmentKey, String attachmentName,
                      String dedupeKey) {
    }

    private static final TypeReference<LinkedHashMap<String, String>> PARAMS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final JsonMapper json;

    MessageOutbox(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Joins the caller's transaction. Returns false when a message with the same dedupe key already exists. */
    boolean enqueue(NewMessage m) {
        return jdbc.sql(QueryUtil.get(QueryConstants.INSERT_MESSAGE_ENQUEUE))
            .param("user", m.userId())
            .param("channel", m.channel().name())
            .param("template", m.template().name())
            .param("dest", truncate(m.destination(), 160))
            .param("subject", truncate(m.subject(), 200))
            .param("body", m.body())
            .param("params", json.writeValueAsString(m.params()))
            .param("sensitive", m.template().sensitive())
            .param("attKey", m.attachmentKey())
            .param("attName", truncate(m.attachmentName(), 120))
            .param("dedupe", truncate(m.dedupeKey(), 200))
            .update() == 1;
    }

    /**
     * Claims due rows for this replica: bumps the attempt count and pushes {@code next_attempt_at} out by the lease,
     * so a crash mid-send simply retries after the lease. SKIP LOCKED keeps replicas from claiming the same rows.
     */
    List<QueuedMessage> claimDue(int limit, Duration lease) {
        return jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_MESSAGE_CLAIM_DUE))
            .param("lease", lease.toSeconds())
            .param("limit", limit)
            .query((rs, i) -> new QueuedMessage(
                rs.getObject("id", UUID.class),
                Channel.valueOf(rs.getString("channel")),
                rs.getString("template"),
                rs.getString("destination"),
                rs.getString("subject"),
                rs.getString("body"),
                json.readValue(rs.getString("params"), PARAMS),
                rs.getString("attachment_key"),
                rs.getString("attachment_name"),
                rs.getInt("attempts")))
            .list();
    }

    void markSent(UUID id, String provider, String providerMessageId) {
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_MESSAGE_SENT))
            .param("provider", provider).param("pid", truncate(providerMessageId, 120)).param("id", id)
            .update();
    }

    void reschedule(UUID id, String provider, Duration delay, String error) {
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_MESSAGE_RESCHEDULE))
            .param("provider", provider).param("error", truncate(error, 500)).param("delay", delay.toSeconds())
            .param("id", id)
            .update();
    }

    void markFailed(UUID id, String provider, String error) {
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_MESSAGE_FAILED))
            .param("provider", provider).param("error", truncate(error, 500)).param("id", id)
            .update();
    }

    static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
