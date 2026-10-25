package com.progenie.messaging;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.progenie.messaging.LiveHub;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.web.PageResponse;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications (the bell icon). Each new one is also pushed to the user's open live streams
 * ({@link LiveHub}) once it is committed, so the bell updates without polling.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    public record NotificationDto(UUID id, String type, String title, @Nullable String body, @Nullable String link, @Nullable OffsetDateTime readAt,
                                  OffsetDateTime createdAt) {
    }

    private final JdbcClient jdbc;
    private final LiveHub live;

    public NotificationService(JdbcClient jdbc, LiveHub live) {
        this.jdbc = jdbc;
        this.live = live;
    }

    @Transactional
    public void notify(UUID userId, String type, String title, String body, String link) {
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_NOTIFICATION_CREATE))
            .param("u", userId).param("type", type).param("title", truncate(title, 120)).param("body", truncate(body, 500))
            .param("link", link)
            .update();
        log.debug("Notified {} ({})", userId, type);
        Map<String, Object> push = push(type, title, link);
        live.afterCommit(() -> live.toUser(userId, "notification", push));
    }

    @Transactional
    public void notifyAdmins(String type, String title, String body, String link) {
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_NOTIFICATION_FOR_ADMINS))
            .param("type", type).param("title", truncate(title, 120)).param("body", truncate(body, 500))
            .param("link", link)
            .update();
        Map<String, Object> push = push(type, title, link);
        live.afterCommit(() -> live.toRole("ADMIN", "notification", push));
    }

    @Transactional(readOnly = true)
    public PageResponse<NotificationDto> list(UUID userId, boolean unreadOnly, int page, int size) {
        Map<String, String> unread = Map.of("unread",
            unreadOnly ? QueryConstants.FRAGMENT_NOTIFICATION_UNREAD_ONLY : QueryConstants.FRAGMENT_NONE);
        long total = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_NOTIFICATION_LIST, unread))
            .param("u", userId).query(Long.class).single();
        List<NotificationDto> items = jdbc.sql(QueryUtil.get(QueryConstants.GET_NOTIFICATION_LIST, unread))
            .param("u", userId)
            .param("limit", PageResponse.clampSize(size)).param("offset", PageResponse.offset(page, size))
            .query(NotificationDto.class).list();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.COUNT_NOTIFICATION_UNREAD))
            .param("u", userId).query(Long.class).single();
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_NOTIFICATION_MARK_READ))
            .param("id", notificationId).param("u", userId).update();
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_NOTIFICATION_MARK_ALL_READ))
            .param("u", userId).update();
    }

    private static Map<String, Object> push(String type, String title, String link) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("type", type);
        data.put("title", truncate(title, 120));
        data.put("link", link);
        return data;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
