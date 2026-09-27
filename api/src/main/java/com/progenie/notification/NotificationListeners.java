package com.progenie.notification;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.progenie.booking.BookingEvent;
import com.progenie.payment.PaymentDtos.PaymentReceived;
import com.progenie.payment.PaymentDtos.RefundProcessed;
import com.progenie.provider.GenieEvents.GenieFlagged;
import com.progenie.provider.GenieEvents.GenieSubmitted;
import com.progenie.provider.GenieEvents.GenieVerificationDecided;
import com.progenie.review.ReviewService.ReviewSubmitted;
import com.progenie.shared.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Turns domain events into notifications. Runs only after the business transaction has committed
 * (so nobody is told about a booking that was rolled back), in its own transaction; a failure here
 * is logged and never breaks the booking or payment itself.
 */
@Component
class NotificationListeners {

    private static final Logger log = LoggerFactory.getLogger(NotificationListeners.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a", Locale.ENGLISH);

    private final NotificationService notifications;
    private final ZoneId zone;

    NotificationListeners(NotificationService notifications, AppProperties props) {
        this.notifications = notifications;
        this.zone = ZoneId.of(props.timezone());
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBooking(BookingEvent e) {
        safely(() -> {
            String when = format(e.slotStart());
            String customerLink = "/account/bookings/" + e.bookingId();
            String genieLink = "/genie/bookings/" + e.bookingId();
            switch (e.type()) {
                case REQUESTED -> notifications.notify(e.genieId(), "BOOKING_REQUESTED", "New booking request",
                    e.bookingRef() + " for " + when + ". Please accept or decline soon.", genieLink);
                case ACCEPTED -> notifications.notify(e.customerId(), "BOOKING_ACCEPTED", "Your booking is confirmed",
                    "Your Genie accepted " + e.bookingRef() + " for " + when + ".", customerLink);
                case DECLINED -> notifications.notify(e.customerId(), "BOOKING_DECLINED", "Your Genie could not take this job",
                    e.bookingRef() + (e.reason() == null ? "" : ": " + e.reason()) + ". Please pick another Genie.", customerLink);
                case EXPIRED -> {
                    notifications.notify(e.customerId(), "BOOKING_EXPIRED", "No response from the Genie",
                        e.bookingRef() + " was not accepted in time. Please pick another Genie.", customerLink);
                    notifications.notify(e.genieId(), "BOOKING_MISSED", "You missed a booking request",
                        e.bookingRef() + " for " + when + " expired without a response.", genieLink);
                }
                case CANCELLED -> {
                    if (!"CUSTOMER".equals(e.actorRole())) {
                        notifications.notify(e.customerId(), "BOOKING_CANCELLED", "Your booking was cancelled",
                            e.bookingRef() + " for " + when + " was cancelled"
                                + ("GENIE".equals(e.actorRole()) ? " by the Genie" : "") + ".", customerLink);
                    }
                    if (!"GENIE".equals(e.actorRole())) {
                        notifications.notify(e.genieId(), "BOOKING_CANCELLED", "Booking cancelled",
                            e.bookingRef() + " for " + when + " was cancelled.", genieLink);
                    }
                }
                case RESCHEDULED -> notifications.notify(e.genieId(), "BOOKING_RESCHEDULED", "Booking moved to a new time",
                    e.bookingRef() + " is now on " + when + ". Please accept it again.", genieLink);
                case STARTED -> notifications.notify(e.customerId(), "BOOKING_STARTED", "Your Genie has started",
                    "Work on " + e.bookingRef() + " has started.", customerLink);
                case COMPLETED -> notifications.notify(e.customerId(), "BOOKING_COMPLETED", "Job done! How was it?",
                    "Please rate your Genie for " + e.bookingRef()
                        + (e.cashCollected() ? "." : ". You can pay in the app."), customerLink);
            }
        });
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPayment(PaymentReceived e) {
        safely(() -> {
            boolean fee = "CANCELLATION_FEE".equals(e.purpose());
            notifications.notify(e.genieId(), "PAYMENT_RECEIVED", fee ? "Cancellation fee received" : "Payment received",
                "₹" + e.amount() + " for " + e.bookingRef() + " (" + e.method().toLowerCase(Locale.ROOT) + ").",
                "/genie/wallet");
            if (!"CASH".equals(e.method())) {
                notifications.notify(e.customerId(), "PAYMENT_SUCCEEDED", "Payment successful",
                    "₹" + e.amount() + " paid for " + e.bookingRef() + ". Thank you!", "/account/bookings/" + e.bookingId());
            }
        });
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRefund(RefundProcessed e) {
        safely(() -> {
            notifications.notify(e.customerId(), "REFUND_PROCESSED", "Refund of ₹" + e.amount() + " processed",
                "For " + e.bookingRef() + ("GATEWAY".equals(e.method())
                    ? ". It reaches your original payment method in 5–7 working days." : ". We sent it to you directly."),
                "/account/bookings/" + e.bookingId());
            if (e.genieShare() != null && e.genieShare().signum() > 0) {
                notifications.notify(e.genieId(), "REFUND_CHARGED", "Refund charged to your earnings",
                    "₹" + e.genieShare() + " was refunded to the customer of " + e.bookingRef() + " and deducted from your wallet.",
                    "/genie/wallet");
            }
        });
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onReview(ReviewSubmitted e) {
        safely(() -> notifications.notify(e.genieId(), "REVIEW_RECEIVED", "New " + e.rating() + "★ review",
            "A customer reviewed your work. You can reply to it.", "/genie/reviews"));
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onGenieSubmitted(GenieSubmitted e) {
        safely(() -> notifications.notifyAdmins("GENIE_SUBMITTED", "New Genie to verify",
            e.fullName() + " submitted their profile and documents.", "/admin/genies/" + e.genieId()));
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onGenieDecision(GenieVerificationDecided e) {
        safely(() -> {
            String note = e.note() == null || e.note().isBlank() ? "" : " Note: " + e.note();
            switch (e.toStatus()) {
                case "APPROVED" -> notifications.notify(e.genieId(), "GENIE_APPROVED", "You're approved!",
                    "Welcome to ProGenie. Go online to start receiving bookings.", "/genie");
                case "NEEDS_CHANGES" -> notifications.notify(e.genieId(), "GENIE_NEEDS_CHANGES", "Please update your profile",
                    "Our team needs a few changes before approving you." + note, "/genie/onboarding");
                case "REJECTED" -> notifications.notify(e.genieId(), "GENIE_REJECTED", "Application not approved",
                    "We could not approve your profile." + note, "/genie");
                case "SUSPENDED" -> notifications.notify(e.genieId(), "GENIE_SUSPENDED", "Your account is suspended",
                    "You will not receive bookings for now." + note, "/genie");
                default -> {
                }
            }
        });
    }

    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onGenieFlagged(GenieFlagged e) {
        safely(() -> notifications.notifyAdmins("GENIE_FLAGGED", "Genie flagged for cancellations",
            e.fullName() + ": " + e.reason(), "/admin/genies/" + e.genieId()));
    }

    private String format(Instant instant) {
        return instant == null ? "" : WHEN.format(instant.atZone(zone));
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.error("Could not create a notification", ex);
        }
    }
}
