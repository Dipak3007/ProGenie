package com.progenie.notification;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.progenie.booking.BookingEvent;
import com.progenie.booking.BookingReminderDue;
import com.progenie.identity.OtpRequested;
import com.progenie.identity.PasswordChanged;
import com.progenie.notification.delivery.Channel;
import com.progenie.notification.delivery.MessageTemplate;
import com.progenie.notification.delivery.Messenger;
import com.progenie.payment.PaymentDtos.PayoutPaid;
import com.progenie.provider.GenieEvents.GenieVerificationDecided;
import com.progenie.shared.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Turns domain events into SMS / WhatsApp / email (design doc 16.1, "who gets what"). Unlike the in-app
 * {@link NotificationListeners}, these run inside the business transaction: the outbox row commits or
 * rolls back together with the change that caused it. They only read and insert, and never throw.
 */
@Component
class MessageListeners {

    private static final Logger log = LoggerFactory.getLogger(MessageListeners.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** What the texts need to know about a booking. */
    record BookingInfo(String bookingRef, String service, String customer, String genie, String area,
                       BigDecimal totalAmount, BigDecimal cancellationFee, String cancellationFeeStatus,
                       String paymentMethod, String paymentStatus) {
    }

    private final Messenger messenger;
    private final JdbcClient jdbc;
    private final ZoneId zone;
    private final long requestMinutes;

    MessageListeners(Messenger messenger, JdbcClient jdbc, AppProperties props) {
        this.messenger = messenger;
        this.jdbc = jdbc;
        this.zone = ZoneId.of(props.timezone());
        this.requestMinutes = props.booking().requestTtl().toMinutes();
    }

    // ------------------------------------------------------------------ accounts

    @EventListener
    public void onOtp(OtpRequested e) {
        safely(() -> {
            String purpose = switch (e.purpose()) {
                case "LOGIN" -> "log in";
                case "RESET_PASSWORD" -> "reset your password";
                case "VERIFY_PHONE" -> "verify your mobile number";
                case "VERIFY_EMAIL" -> "verify your email address";
                default -> "continue";
            };
            messenger.toDestination(e.userId(), e.fullName(), Channel.valueOf(e.channel()), e.destination(),
                MessageTemplate.OTP,
                Map.of("code", e.code(), "purpose", purpose, "minutes", String.valueOf(e.ttlMinutes())),
                "otp:" + e.challengeId());
        });
    }

    @EventListener
    public void onPasswordChanged(PasswordChanged e) {
        safely(() -> messenger.toUser(e.userId(), MessageTemplate.PASSWORD_CHANGED, Set.of(Channel.SMS, Channel.EMAIL),
            Map.of("when", format(e.at()), "link", "/forgot-password"),
            "password-changed:" + e.userId() + ":" + e.at().toEpochMilli()));
    }

    // ------------------------------------------------------------------ bookings

    @EventListener
    public void onBooking(BookingEvent e) {
        safely(() -> {
            BookingInfo b = booking(e.bookingId());
            if (b == null) {
                return;
            }
            Map<String, String> p = params(b, e.slotStart());
            String key = "booking:" + e.bookingId() + ":" + e.type() + ":" + e.slotStart().getEpochSecond();
            String customerLink = "/account/bookings/" + e.bookingId();
            String genieLink = "/genie/bookings/" + e.bookingId();
            String reasonText = e.reason() == null || e.reason().isBlank() ? "" : " Reason: " + e.reason() + ".";
            switch (e.type()) {
                case REQUESTED, RESCHEDULED -> messenger.toUser(e.genieId(), MessageTemplate.BOOKING_REQUEST_GENIE,
                    Set.of(Channel.SMS, Channel.WHATSAPP), with(p, "link", genieLink, "linkLabel", "Accept or decline",
                        "minutes", String.valueOf(requestMinutes)), key);
                case ACCEPTED -> messenger.toUser(e.customerId(), MessageTemplate.BOOKING_ACCEPTED,
                    Set.of(Channel.WHATSAPP), with(p, "link", customerLink), key);
                case DECLINED -> messenger.toUser(e.customerId(), MessageTemplate.BOOKING_DECLINED,
                    Set.of(Channel.WHATSAPP), with(p, "link", "/services", "reasonText", reasonText), key);
                case EXPIRED -> messenger.toUser(e.customerId(), MessageTemplate.BOOKING_EXPIRED,
                    Set.of(Channel.WHATSAPP), with(p, "link", "/services"), key);
                case CANCELLED -> cancelled(e, b, p, key, reasonText, customerLink, genieLink);
                case COMPLETED -> {
                    String payText = "WAIVED".equals(b.paymentStatus()) ? "This redo was free of charge."
                        : e.cashCollected() || "PAID".equals(b.paymentStatus())
                        ? "Thanks for paying ₹" + b.totalAmount() + " in cash."
                        : "₹" + b.totalAmount() + " is due; you can pay in the app.";
                    messenger.toUser(e.customerId(), MessageTemplate.BOOKING_COMPLETED, Set.of(Channel.WHATSAPP),
                        with(p, "link", customerLink, "payText", payText, "linkLabel", "Rate your Genie"), key);
                }
                case STARTED -> {
                }
            }
        });
    }

    private void cancelled(BookingEvent e, BookingInfo b, Map<String, String> p, String key, String reasonText,
                           String customerLink, String genieLink) {
        boolean feeDue = b.cancellationFee() != null && b.cancellationFee().signum() > 0
            && ("DUE".equals(b.cancellationFeeStatus()) || "PAID".equals(b.cancellationFeeStatus()));
        if (!"CUSTOMER".equals(e.actorRole())) {
            String by = "GENIE".equals(e.actorRole()) ? " by your Genie" : "ADMIN".equals(e.actorRole()) ? " by ProGenie" : "";
            messenger.toUser(e.customerId(), MessageTemplate.BOOKING_CANCELLED_CUSTOMER,
                Set.of(Channel.WHATSAPP, Channel.EMAIL),
                with(p, "byText", by, "reasonText", reasonText, "link", customerLink,
                    "feeText", "You haven't been charged. You can book another Genie any time."), key);
        }
        if (!"GENIE".equals(e.actorRole())) {
            String by = "CUSTOMER".equals(e.actorRole()) ? " by the customer" : "ADMIN".equals(e.actorRole()) ? " by ProGenie" : "";
            String fee = feeDue ? " The customer pays you a late-cancellation fee of ₹" + b.cancellationFee() + "." : "";
            messenger.toUser(e.genieId(), MessageTemplate.BOOKING_CANCELLED_GENIE, Set.of(Channel.WHATSAPP),
                with(p, "byText", by, "feeText", fee, "link", genieLink), key);
        }
    }

    @EventListener
    public void onReminder(BookingReminderDue e) {
        safely(() -> {
            BookingInfo b = booking(e.bookingId());
            if (b == null) {
                return;
            }
            Map<String, String> p = params(b, e.slotStart());
            String key = "reminder:" + e.bookingId() + ":" + e.slotStart().getEpochSecond();
            messenger.toUser(e.customerId(), MessageTemplate.BOOKING_REMINDER_CUSTOMER, Set.of(Channel.WHATSAPP),
                with(p, "link", "/account/bookings/" + e.bookingId()), key);
            messenger.toUser(e.genieId(), MessageTemplate.BOOKING_REMINDER_GENIE, Set.of(Channel.WHATSAPP),
                with(p, "link", "/genie/bookings/" + e.bookingId()), key);
        });
    }

    // ------------------------------------------------------------------ Genies

    @EventListener
    public void onGenieDecision(GenieVerificationDecided e) {
        safely(() -> {
            String note = e.note() == null || e.note().isBlank() ? "" : " Note from our team: " + e.note();
            String[] text = switch (e.toStatus()) {
                case "APPROVED" -> new String[] {"You're approved on ProGenie!",
                    "Go online in the app to start receiving bookings."};
                case "NEEDS_CHANGES" -> new String[] {"Please update your ProGenie profile.",
                    "Our team needs a few changes before approving you." + note};
                case "REJECTED" -> new String[] {"Your ProGenie application was not approved.",
                    "We could not approve your profile." + note};
                case "SUSPENDED" -> new String[] {"Your ProGenie account is suspended.",
                    "You won't receive bookings for now." + note};
                default -> null;
            };
            if (text == null) {
                return;
            }
            String link = "NEEDS_CHANGES".equals(e.toStatus()) ? "/genie/onboarding" : "/genie";
            messenger.toUser(e.genieId(), MessageTemplate.GENIE_VERIFICATION, Set.of(Channel.SMS, Channel.EMAIL),
                Map.of("headline", text[0], "detail", text[1], "link", link, "linkLabel", "Open the Genie console"),
                "genie-decision:" + e.genieId() + ":" + e.toStatus() + ":" + System.currentTimeMillis() / 60_000);
        });
    }

    @EventListener
    public void onPayout(PayoutPaid e) {
        safely(() -> messenger.toUser(e.genieId(), MessageTemplate.PAYOUT_SENT, Set.of(Channel.SMS),
            Map.of("amount", e.amount().toPlainString(), "reference", e.reference(),
                "period", DATE.format(e.periodStart()) + " – " + DATE.format(e.periodEnd()), "link", "/genie/wallet"),
            "payout:" + e.payoutId()));
    }

    // ------------------------------------------------------------------ helpers

    private BookingInfo booking(UUID bookingId) {
        return jdbc.sql("""
                SELECT b.booking_ref, s.name AS service, cu.full_name AS customer, gu.full_name AS genie,
                       coalesce(b.address_snapshot ->> 'area', b.address_snapshot ->> 'city', '') AS area,
                       b.total_amount, b.cancellation_fee, b.cancellation_fee_status, b.payment_method, b.payment_status
                  FROM bookings b
                  JOIN services s ON s.id = b.service_id
                  JOIN users cu ON cu.id = b.customer_id
                  JOIN users gu ON gu.id = b.genie_id
                 WHERE b.id = :id
                """)
            .param("id", bookingId)
            .query(BookingInfo.class).optional().orElse(null);
    }

    private Map<String, String> params(BookingInfo b, Instant slotStart) {
        Map<String, String> p = new HashMap<>();
        p.put("ref", b.bookingRef());
        p.put("service", b.service());
        p.put("customer", b.customer());
        p.put("genie", b.genie());
        p.put("area", b.area());
        p.put("amount", b.totalAmount() == null ? "" : b.totalAmount().toPlainString());
        p.put("when", format(slotStart));
        p.put("time", slotStart == null ? "" : TIME.format(slotStart.atZone(zone)));
        return p;
    }

    private static Map<String, String> with(Map<String, String> base, String... kv) {
        Map<String, String> p = new HashMap<>(base);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            p.put(kv[i], kv[i + 1]);
        }
        return p;
    }

    private String format(Instant instant) {
        return instant == null ? "" : WHEN.format(instant.atZone(zone));
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.error("Could not queue a message", ex);
        }
    }
}
