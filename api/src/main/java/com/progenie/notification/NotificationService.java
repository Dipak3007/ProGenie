package com.progenie.notification;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.progenie.shared.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications (the bell icon). Other channels (SMS, e-mail, push) can be added behind
 * {@link #notify} later without touching the modules that raise events.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public record NotificationDto(UUID id, String type, String title, String body, String link, OffsetDateTime readAt,
                                  OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;

    public NotificationService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void notify(UUID userId, String type, String title, String body, String link) {
        jdbc.sql("INSERT INTO notifications (user_id, type, title, body, link) VALUES (:u, :type, :title, :body, :link)")
            .param("u", userId).param("type", type).param("title", truncate(title, 120)).param("body", truncate(body, 500))
            .param("link", link)
            .update();
        log.debug("Notified {} ({})", userId, type);
    }

    @Transactional
    public void notifyAdmins(String type, String title, String body, String link) {
        jdbc.sql("""
                INSERT INTO notifications (user_id, type, title, body, link)
                SELECT id, :type, :title, :body, :link FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE'
                """)
            .param("type", type).param("title", truncate(title, 120)).param("body", truncate(body, 500))
            .param("link", link)
            .update();
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationDto> list(UUID userId, boolean unreadOnly, int page, int size) {
        String where = " WHERE user_id = :u" + (unreadOnly ? " AND read_at IS NULL" : "");
        long total = jdbc.sql("SELECT count(*) FROM notifications" + where).param("u", userId).query(Long.class).single();
        List<NotificationDto> items = jdbc.sql("""
                SELECT id, type, title, body, link, read_at, created_at FROM notifications
                """ + where + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
            .param("u", userId)
            .param("limit", PageResponse.clampSize(size)).param("offset", PageResponse.offset(page, size))
            .query(NotificationDto.class).list();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return jdbc.sql("SELECT count(*) FROM notifications WHERE user_id = :u AND read_at IS NULL")
            .param("u", userId).query(Long.class).single();
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        jdbc.sql("UPDATE notifications SET read_at = now() WHERE id = :id AND user_id = :u AND read_at IS NULL")
            .param("id", notificationId).param("u", userId).update();
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return jdbc.sql("UPDATE notifications SET read_at = now() WHERE user_id = :u AND read_at IS NULL")
            .param("u", userId).update();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
