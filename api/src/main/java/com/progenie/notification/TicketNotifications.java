package com.progenie.notification;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.progenie.notification.delivery.Channel;
import com.progenie.notification.delivery.MessageTemplate;
import com.progenie.notification.delivery.Messenger;
import com.progenie.support.TicketEvents.TicketRaised;
import com.progenie.support.TicketEvents.TicketReplied;
import com.progenie.support.TicketEvents.TicketResolved;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Complaint messages (design doc 16.1): email + in-app to the customer and the Genie, in-app to admins, and an
 * SMS to every admin for a safety report. Emails are queued in the ticket's transaction; in-app notifications
 * are written after it commits, like the rest of the bell.
 */
@Component
class TicketNotifications {

    private static final Logger log = LoggerFactory.getLogger(TicketNotifications.class);

    private final Messenger messenger;
    private final NotificationService notifications;

    TicketNotifications(Messenger messenger, NotificationService notifications) {
        this.messenger = messenger;
        this.notifications = notifications;
    }

    // ------------------------------------------------------------------ outbox (same transaction)

    @EventListener
    public void emailOnRaised(TicketRaised e) {
        safely(() -> {
            boolean privacy = e.bookingId() == null;
            email(e.raisedBy(), e.ticketId(), e.ticketRef(), e.bookingRef(), linkFor(e.raisedBy(), e.customerId(), e.raisedByRole(), e.ticketId()),
                privacy ? "we received your privacy request" : "we received your report",
                privacy ? "Our grievance officer will reply within 30 days: \"" + e.subject() + "\"."
                    : "Thanks for telling us about " + e.bookingRef() + ": \"" + e.subject() + "\". Our team will reply soon, "
                    + ("URGENT".equals(e.priority()) ? "within the hour." : "usually within a day."),
                "raised");
            UUID other = other(e.raisedBy(), e.customerId(), e.genieId());
            if (other != null) {
                email(other, e.ticketId(), e.ticketRef(), e.bookingRef(), linkFor(other, e.customerId(), null, e.ticketId()),
                    "a problem was reported on " + e.bookingRef(),
                    "The " + ("GENIE".equals(e.raisedByRole()) ? "Genie" : "customer") + " reported: \"" + e.subject()
                        + "\". You can read it and reply in the app; our team will look into it.",
                    "raised");
            }
            if ("SAFETY".equals(e.category())) {
                Map<String, String> p = new LinkedHashMap<>();
                p.put("ticketRef", e.ticketRef());
                p.put("ref", e.bookingRef());
                p.put("subject", e.subject());
                p.put("link", "/admin/tickets/" + e.ticketId());
                messenger.toAdmins(MessageTemplate.TICKET_SAFETY_ALERT, Set.of(Channel.SMS, Channel.EMAIL), p,
                    "ticket-safety:" + e.ticketId());
            }
        });
    }

    @EventListener
    public void emailOnReplied(TicketReplied e) {
        safely(() -> {
            String who = switch (e.authorRole()) {
                case "ADMIN" -> "Our team";
                case "GENIE" -> "The Genie";
                default -> "The customer";
            };
            String detail = who + " replied: \"" + e.excerpt() + "\""
                + (e.awaitingReply() ? " Please reply in the app so we can continue." : "");
            String key = "reply:" + java.util.UUID.randomUUID();
            for (UUID to : distinct(e.raisedBy(), e.customerId(), e.genieId())) {
                if (!to.equals(e.authorId())) {
                    email(to, e.ticketId(), e.ticketRef(), e.bookingRef(), linkFor(to, e.customerId(), null, e.ticketId()),
                        e.awaitingReply() ? "we need your reply" : "new reply", detail, key);
                }
            }
        });
    }

    @EventListener
    public void emailOnResolved(TicketResolved e) {
        safely(() -> {
            String headline = "RESOLVED".equals(e.outcome()) ? "your report is resolved" : "your report was closed";
            for (UUID to : distinct(e.raisedBy(), e.customerId(), e.genieId())) {
                email(to, e.ticketId(), e.ticketRef(), e.bookingRef(), linkFor(to, e.customerId(), null, e.ticketId()),
                    headline, e.note() + (to.equals(e.raisedBy()) ? " Not happy with this? You can reopen it within 7 days." : ""),
                    "resolved");
            }
        });
    }

    private void email(UUID to, UUID ticketId, String ticketRef, String bookingRef, String link, String headline,
                       String detail, String key) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("ticketRef", ticketRef);
        p.put("ref", bookingRef == null ? "-" : bookingRef);
        p.put("headline", Character.toUpperCase(headline.charAt(0)) + headline.substring(1));
        p.put("detail", detail);
        p.put("link", link);
        p.put("linkLabel", "Open the report");
        messenger.toUser(to, MessageTemplate.TICKET_UPDATE, Set.of(Channel.EMAIL), p, "ticket:" + ticketId + ":" + key);
    }

    // ------------------------------------------------------------------ in-app (after commit)

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bellOnRaised(TicketRaised e) {
        safely(() -> {
            notifications.notify(e.raisedBy(), "TICKET_RAISED", "Report " + e.ticketRef() + " received",
                "Our team will reply soon.", linkFor(e.raisedBy(), e.customerId(), e.raisedByRole(), e.ticketId()));
            UUID other = other(e.raisedBy(), e.customerId(), e.genieId());
            if (other != null) {
                notifications.notify(other, "TICKET_RAISED", "Problem reported on " + e.bookingRef(), e.subject(),
                    linkFor(other, e.customerId(), null, e.ticketId()));
            }
            notifications.notifyAdmins("TICKET_RAISED",
                ("URGENT".equals(e.priority()) ? "URGENT: " : "") + "New " + e.category().toLowerCase().replace('_', ' ')
                    + " report " + e.ticketRef(), e.subject(), "/admin/tickets/" + e.ticketId());
        });
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bellOnReplied(TicketReplied e) {
        safely(() -> {
            for (UUID to : distinct(e.raisedBy(), e.customerId(), e.genieId())) {
                if (!to.equals(e.authorId())) {
                    notifications.notify(to, "TICKET_REPLY", "New reply on " + e.ticketRef(), e.excerpt(),
                        linkFor(to, e.customerId(), null, e.ticketId()));
                }
            }
            if (!"ADMIN".equals(e.authorRole())) {
                notifications.notifyAdmins("TICKET_REPLY", "New reply on " + e.ticketRef(), e.excerpt(),
                    "/admin/tickets/" + e.ticketId());
            }
        });
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void bellOnResolved(TicketResolved e) {
        safely(() -> {
            for (UUID to : distinct(e.raisedBy(), e.customerId(), e.genieId())) {
                notifications.notify(to, "TICKET_RESOLVED", ("RESOLVED".equals(e.outcome()) ? "Resolved: " : "Closed: ")
                    + e.ticketRef(), e.note(), linkFor(to, e.customerId(), null, e.ticketId()));
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    /** Customers read reports under /account, Genies under /genie. */
    private static String linkFor(UUID user, UUID customerId, String roleHint, UUID ticketId) {
        boolean genie = customerId == null ? "GENIE".equals(roleHint) : !user.equals(customerId);
        return (genie ? "/genie/tickets/" : "/account/tickets/") + ticketId;
    }

    private static UUID other(UUID raisedBy, UUID customerId, UUID genieId) {
        if (customerId == null) {
            return null;
        }
        return raisedBy.equals(customerId) ? genieId : customerId;
    }

    private static Set<UUID> distinct(UUID... ids) {
        Set<UUID> out = new java.util.LinkedHashSet<>();
        for (UUID id : ids) {
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException ex) {
            log.error("Could not notify about a ticket", ex);
        }
    }
}
