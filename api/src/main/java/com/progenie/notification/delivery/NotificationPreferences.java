package com.progenie.notification.delivery;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which channels a user wants per message category. No row means the default: everything on, except
 * promotions (opt-in only). Mandatory messages (codes, security notices, the Genie's new-request alert)
 * ignore these switches.
 */
@Service
public class NotificationPreferences {

    public record PreferenceDto(MessageCategory category, boolean sms, boolean whatsapp, boolean email) {
    }

    private final JdbcClient jdbc;

    public NotificationPreferences(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    static PreferenceDto defaults(MessageCategory category) {
        boolean on = category != MessageCategory.PROMOTIONS;
        return new PreferenceDto(category, on, on, on);
    }

    @Transactional(readOnly = true)
    public List<PreferenceDto> list(UUID userId) {
        Map<MessageCategory, PreferenceDto> stored = new EnumMap<>(MessageCategory.class);
        jdbc.sql("SELECT category, sms, whatsapp, email FROM notification_preferences WHERE user_id = :u")
            .param("u", userId)
            .query(PreferenceDto.class).list()
            .forEach(p -> stored.put(p.category(), p));
        List<PreferenceDto> out = new ArrayList<>();
        for (MessageCategory c : MessageCategory.values()) {
            out.add(stored.getOrDefault(c, defaults(c)));
        }
        return out;
    }

    @Transactional
    public List<PreferenceDto> update(UUID userId, List<PreferenceDto> changes) {
        for (PreferenceDto p : changes) {
            if (p.category() == null) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO notification_preferences (user_id, category, sms, whatsapp, email)
                    VALUES (:u, :c, :sms, :wa, :email)
                    ON CONFLICT (user_id, category)
                    DO UPDATE SET sms = EXCLUDED.sms, whatsapp = EXCLUDED.whatsapp, email = EXCLUDED.email, updated_at = now()
                    """)
                .param("u", userId).param("c", p.category().name())
                .param("sms", p.sms()).param("wa", p.whatsapp()).param("email", p.email())
                .update();
        }
        return list(userId);
    }

    @Transactional(readOnly = true)
    public boolean allows(UUID userId, MessageCategory category, Channel channel) {
        PreferenceDto p = jdbc.sql("""
                SELECT category, sms, whatsapp, email FROM notification_preferences WHERE user_id = :u AND category = :c
                """)
            .param("u", userId).param("c", category.name())
            .query(PreferenceDto.class).optional()
            .orElseGet(() -> defaults(category));
        return switch (channel) {
            case SMS -> p.sms();
            case WHATSAPP -> p.whatsapp();
            case EMAIL -> p.email();
        };
    }

    /** Account deletion removes preferences with the rest of the personal data. */
    @Transactional
    public void deleteAll(UUID userId) {
        jdbc.sql("DELETE FROM notification_preferences WHERE user_id = :u").param("u", userId).update();
    }
}
