package com.progenie.provider;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.progenie.messaging.LiveHub;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Online" means "ProGenie is open on my phone" (Phase 3C-9). Genie Now offers reach a Genie only over the live
 * stream while their ProGenie page is open, so a Genie whose page has been closed for {@code offlineAfter}
 * (2 minutes) is switched offline and gets no offers.
 *
 * <p>Every run marks the Genies with an open stream on this instance as seen ({@code live_seen_at}); then, on one
 * replica (job lock), switches off the online Genies not seen for {@code offlineAfter}. The first run waits a little
 * after start-up so open pages can reconnect to a restarted API first. The Android app will replace this with push.
 */
@Component
public class GeniePresence {

    private static final Logger log = LoggerFactory.getLogger(GeniePresence.class);

    private final JdbcClient jdbc;
    private final LiveHub live;
    private final JobLock lock;
    private final CacheManager caches;
    private final Clock clock;
    private final Duration offlineAfter;

    public GeniePresence(JdbcClient jdbc, LiveHub live, JobLock lock, CacheManager caches, Clock clock,
                         @Value("${progenie.genie.offline-after:2m}") Duration offlineAfter) {
        this.jdbc = jdbc;
        this.live = live;
        this.lock = lock;
        this.caches = caches;
        this.clock = clock;
        this.offlineAfter = offlineAfter;
    }

    @Scheduled(fixedDelayString = "${progenie.genie.presence-interval:30s}",
        initialDelayString = "${progenie.genie.presence-initial-delay:2m}")
    @Transactional
    public void sweep() {
        List<UUID> connected = live.connectedUsers("GENIE");
        if (!connected.isEmpty()) {
            jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_GENIE_PROFILE_LIVE_SEEN)).param("ids", connected).update();
        }
        if (!lock.tryAcquire("genie-presence")) {
            return;
        }
        List<UUID> off = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_GENIE_PROFILE_OFFLINE_UNSEEN))
            .param("cutoff", Times.odt(clock.instant().minus(offlineAfter)))
            .query(UUID.class).list();
        if (off.isEmpty()) {
            return;
        }
        log.info("Switched {} Genie(s) offline: ProGenie closed for more than {} min", off.size(), offlineAfter.toMinutes());
        for (String name : List.of(GenieCaches.LIST, GenieCaches.DETAIL)) {
            Cache cache = caches.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }
        live.afterCommit(() -> off.forEach(id -> live.toUser(id, "presence", Map.of("online", false))));
    }
}
