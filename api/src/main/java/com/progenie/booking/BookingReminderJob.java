package com.progenie.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every minute: accepted bookings whose slot starts within the next hour get one reminder
 * ({@link BookingReminderDue}), stamped with {@code reminder_sent_at} in the same transaction so it
 * is never sent twice.
 */
@Component
class BookingReminderJob {

    private static final Logger log = LoggerFactory.getLogger(BookingReminderJob.class);

    record Due(UUID id, String bookingRef, UUID customerId, UUID genieId, OffsetDateTime slotStart) {
    }

    private final JdbcClient jdbc;
    private final JobLock lock;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final Duration lead;

    BookingReminderJob(JdbcClient jdbc, JobLock lock, ApplicationEventPublisher events, Clock clock,
                       @Value("${progenie.booking.reminder-lead:60m}") Duration lead) {
        this.jdbc = jdbc;
        this.lock = lock;
        this.events = events;
        this.clock = clock;
        this.lead = lead;
    }

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT30S")
    @Transactional
    public void sendReminders() {
        if (!lock.tryAcquire("booking-reminders")) {
            return;
        }
        Instant now = clock.instant();
        List<Due> due = jdbc.sql("""
                SELECT id, booking_ref, customer_id, genie_id, slot_start FROM bookings
                 WHERE status = 'ACCEPTED' AND reminder_sent_at IS NULL
                   AND slot_start > :now AND slot_start <= :until
                 ORDER BY slot_start
                 LIMIT 200
                 FOR UPDATE SKIP LOCKED
                """)
            .param("now", Times.odt(now)).param("until", Times.odt(now.plus(lead)))
            .query(Due.class).list();
        for (Due d : due) {
            jdbc.sql("UPDATE bookings SET reminder_sent_at = now() WHERE id = :id").param("id", d.id()).update();
            events.publishEvent(new BookingReminderDue(d.id(), d.bookingRef(), d.customerId(), d.genieId(),
                d.slotStart().toInstant()));
        }
        if (!due.isEmpty()) {
            log.info("Queued reminders for {} bookings", due.size());
        }
    }
}
