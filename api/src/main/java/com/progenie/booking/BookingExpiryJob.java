package com.progenie.booking;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import com.progenie.booking.domain.Booking;
import com.progenie.booking.domain.BookingStatus;
import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every minute: requests the Genie did not answer in time become EXPIRED (the slot is freed and the
 * customer is told to pick someone else). Runs on one replica at a time; rows already locked by a
 * user action are skipped and picked up next minute.
 */
@Component
class BookingExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(BookingExpiryJob.class);
    private static final int BATCH = 200;

    private final JdbcClient jdbc;
    private final JobLock lock;
    private final BookingSupport support;
    private final Clock clock;

    BookingExpiryJob(JdbcClient jdbc, JobLock lock, BookingSupport support, Clock clock) {
        this.jdbc = jdbc;
        this.lock = lock;
        this.support = support;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT20S")
    @Transactional
    public void expireUnansweredRequests() {
        if (!lock.tryAcquire("booking-expiry")) {
            return;
        }
        List<UUID> due = jdbc.sql("""
                SELECT id FROM bookings
                 WHERE status = 'REQUESTED' AND expires_at <= :now
                 ORDER BY expires_at
                 LIMIT :batch
                 FOR UPDATE SKIP LOCKED
                """)
            .param("now", Times.odt(clock.instant()))
            .param("batch", BATCH)
            .query(UUID.class)
            .list();
        for (UUID id : due) {
            Booking b = support.lock(id);
            b.expire();
            support.transitioned(b, BookingStatus.REQUESTED, null, "SYSTEM", BookingEvent.Type.EXPIRED,
                "The Genie did not respond in time", false);
        }
        if (!due.isEmpty()) {
            log.info("Expired {} unanswered booking requests", due.size());
        }
    }
}
