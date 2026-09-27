package com.progenie.notification.delivery;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

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
        return jdbc.sql("""
                INSERT INTO outbound_messages (user_id, channel, template, destination, subject, body, params, sensitive,
                                               attachment_key, attachment_name, dedupe_key)
                VALUES (:user, :channel, :template, :dest, :subject, :body, CAST(:params AS jsonb), :sensitive,
                        :attKey, :attName, :dedupe)
                ON CONFLICT (dedupe_key) DO NOTHING
                """)
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
        return jdbc.sql("""
                UPDATE outbound_messages m
                   SET attempts = m.attempts + 1, next_attempt_at = now() + make_interval(secs => :lease)
                 WHERE m.id IN (SELECT id FROM outbound_messages
                                 WHERE status = 'PENDING' AND next_attempt_at <= now()
                                 ORDER BY next_attempt_at
                                 LIMIT :limit
                                 FOR UPDATE SKIP LOCKED)
                RETURNING m.id, m.channel, m.template, m.destination, m.subject, m.body, m.params::text AS params,
                          m.attachment_key, m.attachment_name, m.attempts
                """)
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
        jdbc.sql("""
                UPDATE outbound_messages
                   SET status = 'SENT', provider = :provider, provider_message_id = :pid, sent_at = now(), last_error = NULL,
                       body = CASE WHEN sensitive THEN '[redacted]' ELSE body END,
                       params = CASE WHEN sensitive THEN '{}'::jsonb ELSE params END
                 WHERE id = :id
                """)
            .param("provider", provider).param("pid", truncate(providerMessageId, 120)).param("id", id)
            .update();
    }

    void reschedule(UUID id, String provider, Duration delay, String error) {
        jdbc.sql("""
                UPDATE outbound_messages
                   SET provider = :provider, last_error = :error, next_attempt_at = now() + make_interval(secs => :delay)
                 WHERE id = :id
                """)
            .param("provider", provider).param("error", truncate(error, 500)).param("delay", delay.toSeconds())
            .param("id", id)
            .update();
    }

    void markFailed(UUID id, String provider, String error) {
        jdbc.sql("""
                UPDATE outbound_messages
                   SET status = 'FAILED', provider = :provider, last_error = :error,
                       body = CASE WHEN sensitive THEN '[redacted]' ELSE body END,
                       params = CASE WHEN sensitive THEN '{}'::jsonb ELSE params END
                 WHERE id = :id
                """)
            .param("provider", provider).param("error", truncate(error, 500)).param("id", id)
            .update();
    }

    static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
