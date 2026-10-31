package com.progenie.provider;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Genie badges (Phase 3A-7), recalculated every night for all approved Genies; a badge is lost when the numbers drop.
 * <ul>
 *   <li>{@code TOP_GENIE}: rating at least 4.8 from at least 20 ratings, and under 5% of the jobs accepted in the
 *       last 90 days cancelled by the Genie</li>
 *   <li>{@code JOBS_100} / {@code JOBS_500}: completed jobs (500 replaces 100)</li>
 *   <li>{@code QUICK_RESPONDER}: median time from request to acceptance at most 10 minutes over the last 90 days
 *       (needs at least 5 accepted requests)</li>
 *   <li>{@code NEW}: first 30 days after approval</li>
 * </ul>
 * Top Genie and Quick responder each add 0.25 to the rating used for the default "Recommended" order.
 */
@Service
public class GenieBadges {

    private static final Logger log = LoggerFactory.getLogger(GenieBadges.class);

    public static final List<String> ALL = List.of("TOP_GENIE", "JOBS_100", "JOBS_500", "QUICK_RESPONDER", "NEW");

    static final Duration WINDOW = Duration.ofDays(90);
    static final Duration NEW_PERIOD = Duration.ofDays(30);
    static final double TOP_RATING = 4.8;
    static final int TOP_MIN_RATINGS = 20;
    static final int TOP_MAX_CANCEL_PERCENT = 5;
    static final int QUICK_MIN_ACCEPTED = 5;
    static final Duration QUICK_MEDIAN = Duration.ofMinutes(10);

    private final JdbcClient jdbc;
    private final JobLock lock;
    private final Clock clock;

    public GenieBadges(JdbcClient jdbc, JobLock lock, Clock clock) {
        this.jdbc = jdbc;
        this.lock = lock;
        this.clock = clock;
    }

    /** Nightly run (one replica, thanks to the job lock); also the admin "Recalculate badges" button. */
    @Scheduled(cron = "${progenie.badges.cron:0 15 2 * * *}", zone = "${progenie.timezone}")
    @Transactional
    @GenieCaches.EvictsGenies
    public int recalculate() {
        return compute("nightly");
    }

    /** The first start after the V7 migration fills the badges at once instead of waiting for the night. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    @GenieCaches.EvictsGenies
    public void fillOnFirstStart() {
        boolean computed = jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_GENIE_PROFILE_BADGES_COMPUTED))
            .query(Boolean.class).single();
        if (!computed) {
            compute("first start");
        }
    }

    /** A Genie's current badges (empty when none). */
    @Transactional(readOnly = true)
    public List<String> of(UUID genieId) {
        String csv = jdbc.sql(QueryUtil.get(QueryConstants.GET_GENIE_PROFILE_BADGES)).param("id", genieId)
            .query(String.class).optional().orElse("");
        return StringUtils.hasText(csv) ? List.of(csv.split(",")) : List.of();
    }

    /** Must run inside a transaction (the job lock is transaction-scoped). */
    private int compute(String trigger) {
        if (!lock.tryAcquire("genie-badges")) {
            return 0;
        }
        var now = clock.instant();
        int genies = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_GENIE_PROFILE_BADGES))
            .param("since", Times.odt(now.minus(WINDOW)))
            .param("newSince", Times.odt(now.minus(NEW_PERIOD)))
            .param("topRating", TOP_RATING)
            .param("topMinRatings", TOP_MIN_RATINGS)
            .param("topMaxCancelPercent", TOP_MAX_CANCEL_PERCENT)
            .param("quickMinAccepted", QUICK_MIN_ACCEPTED)
            .param("quickSeconds", QUICK_MEDIAN.toSeconds())
            .update();
        int cleared = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_GENIE_PROFILE_CLEAR_BADGES)).update();
        log.info("Badges recalculated ({}): {} approved Genies, {} others cleared", trigger, genies, cleared);
        return genies;
    }
}
