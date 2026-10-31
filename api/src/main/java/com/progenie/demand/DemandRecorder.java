package com.progenie.demand;

import java.time.Duration;
import java.util.UUID;

import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.ratelimit.RateLimiter;
import com.progenie.shared.security.CurrentUser;
import com.progenie.shared.util.QueryUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records a moment a customer wanted something we could not give (Phase 3D), for the admin demand heatmap.
 *
 * <p>Never breaks the request that triggered it: it runs in its own transaction and any failure is only logged.
 * Stores no personal data (no user, no IP): just what, where (PIN code or map point) and when. The caller passes
 * {@code who} (a user ID or IP address) only for rate limiting: the same thing from the same person counts once per
 * 30 minutes, and one person adds at most 30 events an hour, so refreshing a page can't paint the map.
 */
@Service
public class DemandRecorder {

    private static final Logger log = LoggerFactory.getLogger(DemandRecorder.class);
    static final Duration REPEAT_WINDOW = Duration.ofMinutes(30);
    static final int MAX_PER_HOUR = 30;

    public enum Kind { PINCODE_UNSERVED, LOCATION_UNSERVED, NO_GENIE_LISTED, INSTANT_NONE_FREE }

    /** What was wanted; every field but {@code kind} is optional. */
    public record Event(Kind kind, @Nullable Long cityId, @Nullable Long categoryId, @Nullable Long serviceId,
                        @Nullable String pincode, @Nullable Double lat, @Nullable Double lng, @Nullable String reason) {

        public static Event pincode(String pincode) {
            return new Event(Kind.PINCODE_UNSERVED, null, null, null, pincode, null, null, null);
        }

        public static Event location(double lat, double lng) {
            return new Event(Kind.LOCATION_UNSERVED, null, null, null, null, lat, lng, null);
        }
    }

    private final JdbcClient jdbc;
    private final RateLimiter limiter;
    private final TransactionTemplate tx;

    public DemandRecorder(JdbcClient jdbc, RateLimiter limiter, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.limiter = limiter;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Who is asking, for rate limiting only (never stored): the signed-in user, else the client address. */
    public static String who(HttpServletRequest request) {
        UUID user = CurrentUser.idOrNull();
        return user != null ? user.toString() : String.valueOf(request.getRemoteAddr());
    }

    public void record(Event e, String who) {
        String key = e.kind() + "|" + e.cityId() + "|" + e.categoryId() + "|" + e.serviceId() + "|" + e.pincode() + "|"
            + (e.lat() == null ? "" : Math.round(e.lat() * 1000) + "," + Math.round(e.lng() * 1000)) + "|" + e.reason();
        safely(who, key, () -> jdbc.sql(QueryUtil.get(QueryConstants.INSERT_DEMAND_EVENT))
            .param("kind", e.kind().name()).param("cityId", e.cityId()).param("categoryId", e.categoryId())
            .param("serviceId", e.serviceId()).param("pincode", e.pincode()).param("lat", e.lat()).param("lng", e.lng())
            .param("reason", e.reason())
            .update());
    }

    /**
     * A category or service page with no Genie in the city. Slugs are resolved here; a signed-in customer's default
     * address gives the PIN code and map point (only those are stored, not who it was).
     */
    public void recordNoGenieListed(String citySlug, @Nullable String categorySlug, @Nullable Long serviceId,
                                    @Nullable UUID customerId, String who) {
        String key = Kind.NO_GENIE_LISTED + "|" + citySlug + "|" + categorySlug + "|" + serviceId;
        safely(who, key, () -> jdbc.sql(QueryUtil.get(QueryConstants.INSERT_DEMAND_NO_GENIE_LISTED))
            .param("city", citySlug).param("category", categorySlug).param("serviceId", serviceId)
            .param("customerId", customerId)
            .update());
    }

    private void safely(String who, String key, Runnable insert) {
        try {
            tx.executeWithoutResult(status -> {
                String same = key.length() > 120 ? Integer.toHexString(key.hashCode()) : key; // counter keys are short
                if (!limiter.tryAcquire("demand:" + who, MAX_PER_HOUR, Duration.ofHours(1))
                    || !limiter.tryAcquire("demand:" + who + ":" + same, 1, REPEAT_WINDOW)) {
                    return;
                }
                insert.run();
            });
        } catch (RuntimeException ex) {
            log.warn("Could not record unmet demand ({})", ex.getMessage());
        }
    }
}
