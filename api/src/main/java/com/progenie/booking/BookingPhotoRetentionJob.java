package com.progenie.booking;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Nightly: job photos are deleted 1 year after the job, unless a complaint about the booking is still open. */
@Component
public class BookingPhotoRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(BookingPhotoRetentionJob.class);
    static final Duration KEEP = Duration.ofDays(365);

    private final JdbcClient jdbc;
    private final BookingPhotos photos;
    private final JobLock lock;
    private final Clock clock;

    public BookingPhotoRetentionJob(JdbcClient jdbc, BookingPhotos photos, JobLock lock, Clock clock) {
        this.jdbc = jdbc;
        this.photos = photos;
        this.lock = lock;
        this.clock = clock;
    }

    @Scheduled(cron = "${progenie.booking.photo-retention-cron:0 40 3 * * *}", zone = "${progenie.timezone}")
    @Transactional
    public int run() {
        if (!lock.tryAcquire("booking-photo-retention")) {
            return 0;
        }
        record Expired(UUID id, String objectKey) {
        }
        List<Expired> expired = jdbc.sql(QueryUtil.get(QueryConstants.GET_BOOKING_EXPIRED_PHOTOS))
            .param("before", Times.odt(clock.instant().minus(KEEP))).query(Expired.class).list();
        for (Expired p : expired) {
            jdbc.sql(QueryUtil.get(QueryConstants.DELETE_BOOKING_PHOTO)).param("id", p.id()).update();
            photos.deleteFile(p.objectKey());
        }
        if (!expired.isEmpty()) {
            log.info("Photo retention: {} job photos older than a year removed", expired.size());
        }
        return expired.size();
    }
}
