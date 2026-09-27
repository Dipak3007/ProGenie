package com.progenie.notification.delivery;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.progenie.notification.delivery.MessageOutbox.NewMessage;
import com.progenie.shared.util.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The one entry point other code uses to send SMS, WhatsApp and email. It never talks to a provider:
 * it renders the text and writes an outbox row in the caller's transaction, so a rolled-back change
 * sends nothing and a committed one is never lost. {@link MessageDispatcher} does the sending.
 *
 * <p>Rules applied here: deleted accounts get nothing; email goes only to a verified address (codes sent
 * to a specific address use {@link #toDestination}); optional messages respect the user's preferences;
 * the dedupe key makes a repeated call harmless.
 */
@Service
public class Messenger {

    private static final Logger log = LoggerFactory.getLogger(Messenger.class);

    /** A stored file to attach (emails only), e.g. a receipt PDF. */
    public record Attachment(String key, String fileName) {
    }

    record Contact(UUID id, String fullName, String phone, String email, boolean emailVerified, String status) {
    }

    private final JdbcClient jdbc;
    private final MessageOutbox outbox;
    private final NotificationPreferences preferences;
    private final MessagingProperties props;

    public Messenger(JdbcClient jdbc, MessageOutbox outbox, NotificationPreferences preferences, MessagingProperties props) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.preferences = preferences;
        this.props = props;
    }

    @Transactional
    public void toUser(UUID userId, MessageTemplate template, Set<Channel> channels, Map<String, String> params,
                       String dedupeKey) {
        toUser(userId, template, channels, params, dedupeKey, null);
    }

    @Transactional
    public void toUser(UUID userId, MessageTemplate template, Set<Channel> channels, Map<String, String> params,
                       String dedupeKey, Attachment attachment) {
        if (userId == null) {
            return;
        }
        Contact c = contact(userId);
        if (c == null || "DELETED".equals(c.status())) {
            return;
        }
        for (Channel channel : channels) {
            String destination = switch (channel) {
                case SMS, WHATSAPP -> c.phone();
                case EMAIL -> c.emailVerified() ? c.email() : null;
            };
            if (!StringUtils.hasText(destination)) {
                continue;
            }
            if (!template.mandatory() && !preferences.allows(userId, template.category(), channel)) {
                continue;
            }
            enqueue(userId, c.fullName(), channel, destination, template, params, dedupeKey, attachment);
        }
    }

    /**
     * Sends to an explicit phone number or email address (one-time codes, or a new address being verified).
     * {@code userId} may be null when the address isn't tied to an account.
     */
    @Transactional
    public void toDestination(UUID userId, String fullName, Channel channel, String destination, MessageTemplate template,
                              Map<String, String> params, String dedupeKey) {
        enqueue(userId, fullName, channel, destination, template, params, dedupeKey, null);
    }

    /** Every active admin (e.g. the SMS for an urgent safety complaint). */
    @Transactional
    public void toAdmins(MessageTemplate template, Set<Channel> channels, Map<String, String> params, String dedupeKey) {
        List<UUID> admins = jdbc.sql("SELECT id FROM users WHERE role = 'ADMIN' AND status = 'ACTIVE'")
            .query(UUID.class).list();
        admins.forEach(id -> toUser(id, template, channels, params, dedupeKey));
    }

    private void enqueue(UUID userId, String fullName, Channel channel, String destination, MessageTemplate template,
                         Map<String, String> params, String dedupeKey, Attachment attachment) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("name", firstName(fullName));
        params.forEach((k, v) -> p.put(k, v == null ? "" : v));
        String link = p.get("link");
        if (link != null && link.startsWith("/")) {
            p.put("link", props.webBaseUrl() + link);
        }
        MessageRenderer.Rendered r = MessageRenderer.render(template, channel, p);
        boolean queued = outbox.enqueue(new NewMessage(userId, channel, template, destination, r.subject(), r.body(), p,
            attachment == null ? null : attachment.key(), attachment == null ? null : attachment.fileName(),
            dedupeKey + ":" + channel + ":" + (userId == null ? destination : userId)));
        if (queued) {
            log.debug("Queued {} {} for {}", channel, template, Masking.destination(destination));
        }
    }

    private Contact contact(UUID userId) {
        return jdbc.sql("SELECT id, full_name, phone, email, email_verified, status FROM users WHERE id = :id")
            .param("id", userId)
            .query(Contact.class).optional().orElse(null);
    }

    static String firstName(String fullName) {
        if (!StringUtils.hasText(fullName)) {
            return "there";
        }
        return fullName.trim().split("\\s+")[0];
    }
}
