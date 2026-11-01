package com.progenie.demand;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.ratelimit.RateLimiter;
import com.progenie.shared.util.Phones;
import com.progenie.shared.util.QueryUtil;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * "Notify me when ProGenie arrives" (Phase 3.5B). Anyone can leave a phone number or an e-mail address for a PIN code
 * we don't serve (or a map point outside every live city), agreeing to be contacted once. When that PIN code joins a
 * live city, or a live city starts covering the point, the notification module claims the alerts ({@link #claimOpened})
 * and sends one message; the alert is deleted with it. Alerts nobody could use are deleted after 12 months.
 */
@Service
public class LaunchAlerts {

    private static final Logger log = LoggerFactory.getLogger(LaunchAlerts.class);

    static final Duration KEEP_FOR = Duration.ofDays(365);
    static final int PER_HOUR = 5;

    public record LaunchAlertRequest(@Size(max = 6) String pincode,
                                     @DecimalMin("-90") @DecimalMax("90") Double lat,
                                     @DecimalMin("-180") @DecimalMax("180") Double lng,
                                     @Size(max = 20) String phone,
                                     @Size(max = 254) String email,
                                     boolean consent) {
    }

    /** What the person sees: where we'll tell them about (a masked contact, never the full one). */
    public record LaunchAlertDto(@Nullable String pincode, String contact, String channel) {
    }

    /** An alert that became due because its area opened, for the notification module to send. */
    public record OpenedAlert(@Nullable String pincode, @Nullable String phone, @Nullable String email, String place,
                              String citySlug) {
    }

    /** "People waiting" for the admin demand map. */
    public record LaunchWaiting(String pincode, int count) {
    }

    /** The PIN codes most people are waiting for, and everyone waiting (map points included). */
    public record LaunchWaitingSummary(List<LaunchWaiting> top, int total) {
    }

    private final JdbcClient jdbc;
    private final RateLimiter limiter;
    private final JobLock lock;

    public LaunchAlerts(JdbcClient jdbc, RateLimiter limiter, JobLock lock) {
        this.jdbc = jdbc;
        this.limiter = limiter;
        this.lock = lock;
    }

    @Transactional
    public LaunchAlertDto signUp(@Nullable UUID userId, String who, LaunchAlertRequest req) {
        if (!req.consent()) {
            throw ApiException.badRequest("CONSENT_NEEDED", "Tick the box so we may contact you once when we arrive");
        }
        String pincode = StringUtils.hasText(req.pincode()) ? req.pincode().trim() : null;
        boolean point = req.lat() != null && req.lng() != null;
        if (pincode == null && !point) {
            throw ApiException.badRequest("WHERE_NEEDED", "Give a PIN code or a place on the map");
        }
        if (pincode != null && !pincode.matches("[1-9][0-9]{5}")) {
            throw ApiException.badRequest("INVALID_PINCODE", "Enter a valid 6-digit PIN code");
        }
        String phone = null;
        String email = null;
        if (StringUtils.hasText(req.phone()) == StringUtils.hasText(req.email())) {
            throw ApiException.badRequest("CONTACT_NEEDED", "Give a mobile number or an e-mail address (one of them)");
        }
        if (StringUtils.hasText(req.phone())) {
            phone = Phones.national(req.phone());
            if (phone == null || !phone.matches("[6-9][0-9]{9}")) {
                throw ApiException.badRequest("INVALID_PHONE", "Enter a 10-digit Indian mobile number");
            }
        } else {
            email = req.email().trim().toLowerCase(Locale.ROOT);
            if (!email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
                throw ApiException.badRequest("INVALID_EMAIL", "Enter a valid e-mail address");
            }
        }
        if (pincode != null) {
            if (jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_LAUNCH_ALERT_PINCODE_SERVED)).param("pincode", pincode)
                .query(Boolean.class).single()) {
                throw ApiException.conflict("ALREADY_SERVED", "Good news: we already serve " + pincode);
            }
        } else {
            if (!jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_SERVICEABILITY_IN_INDIA)).param("lat", req.lat())
                .param("lng", req.lng()).query(Boolean.class).single()) {
                throw ApiException.unprocessable("LOCATION_OUTSIDE_INDIA", "ProGenie is only in India for now");
            }
            if (jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_LAUNCH_ALERT_POINT_SERVED)).param("lat", req.lat())
                .param("lng", req.lng()).query(Boolean.class).single()) {
                throw ApiException.conflict("ALREADY_SERVED", "Good news: we already serve this place");
            }
        }
        if (!limiter.tryAcquire("launch-alert:" + who, PER_HOUR, Duration.ofHours(1))) {
            throw ApiException.tooManyRequests("RATE_LIMITED", "Too many requests. Try again in a while");
        }
        jdbc.sql(QueryUtil.get(QueryConstants.INSERT_LAUNCH_ALERT))
            .param("pincode", pincode)
            .param("lat", pincode == null ? req.lat() : null).param("lng", pincode == null ? req.lng() : null)
            .param("phone", phone).param("email", email).param("u", userId)
            .update(); // a repeat for the same PIN code and contact is ignored
        log.info("Launch alert for {} ({})", pincode != null ? pincode : "a map point", phone != null ? "phone" : "e-mail");
        return new LaunchAlertDto(pincode, phone != null ? "••••••" + phone.substring(6) : maskEmail(email),
            phone != null ? "PHONE" : "EMAIL");
    }

    /**
     * Claims (deletes and returns) the alerts a live city now covers: its served PIN codes, and map points inside its
     * radius. Nothing for a city that is switched off. Called in the admin's transaction, so the messages are queued
     * together with the change.
     */
    @Transactional
    public List<OpenedAlert> claimOpened(long cityId) {
        List<OpenedAlert> due = jdbc.sql(QueryUtil.get(QueryConstants.DELETE_LAUNCH_ALERT_OPENED)).param("cityId", cityId)
            .query(OpenedAlert.class).list();
        if (!due.isEmpty()) {
            log.info("City {} opened for {} people who asked to be told", cityId, due.size());
        }
        return due;
    }

    /** People waiting, per PIN code (most first), for the admin demand map. */
    @Transactional(readOnly = true)
    public LaunchWaitingSummary waiting() {
        List<LaunchWaiting> top = jdbc.sql(QueryUtil.get(QueryConstants.GET_LAUNCH_ALERT_WAITING)).query(LaunchWaiting.class).list();
        int total = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_LAUNCH_ALERT)).query(Integer.class).single();
        return new LaunchWaitingSummary(top, total);
    }

    /** Alerts for an area that never opened are deleted after {@link #KEEP_FOR}. */
    @Scheduled(cron = "0 40 3 * * *", zone = "${progenie.timezone}")
    @Transactional
    public void deleteOld() {
        if (!lock.tryAcquire("launch-alerts-expiry")) {
            return;
        }
        int removed = jdbc.sql(QueryUtil.get(QueryConstants.DELETE_LAUNCH_ALERT_EXPIRED))
            .param("days", (int) KEEP_FOR.toDays()).update();
        if (removed > 0) {
            log.info("Launch alerts: {} older than {} days deleted unsent", removed, KEEP_FOR.toDays());
        }
    }

    static String maskEmail(String email) {
        int at = email.indexOf('@');
        return (at <= 1 ? "•" : email.charAt(0) + "•••") + email.substring(at);
    }
}
