package com.progenie.identity.privacy;

import java.util.List;
import java.util.UUID;

import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.storage.FileStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Nightly clean-up of data past its retention period (design doc 16.5). Financial records (bookings, payments,
 * receipts, ledger) are kept 8 years and are not touched here yet: the first ones reach that age in 2034.
 * <ul>
 *   <li>Genie KYC files: 30 days after account deletion, 180 days after a rejected application</li>
 *   <li>Complaint tickets with their photos: 3 years after closing</li>
 *   <li>Sent SMS / WhatsApp / email copies: 90 days</li>
 *   <li>Used or expired one-time codes and reset tokens: 1 day; processed webhook ids: 90 days</li>
 * </ul>
 */
@Component
class RetentionJob {

    private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final JobLock lock;

    RetentionJob(JdbcClient jdbc, FileStorage storage, JobLock lock) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.lock = lock;
    }

    @Scheduled(cron = "${progenie.privacy.retention-cron:0 30 3 * * *}", zone = "${progenie.timezone}")
    @Transactional
    public void run() {
        if (!lock.tryAcquire("retention")) {
            return;
        }
        int kyc = deleteKycFiles();
        int tickets = deleteOldTickets();
        int messages = jdbc.sql("DELETE FROM outbound_messages WHERE created_at < now() - interval '90 days' AND status <> 'PENDING'").update();
        int otps = jdbc.sql("DELETE FROM otp_challenges WHERE expires_at < now() - interval '1 day'").update();
        int resets = jdbc.sql("DELETE FROM password_reset_tokens WHERE expires_at < now() - interval '1 day'").update();
        int hooks = jdbc.sql("DELETE FROM webhook_events WHERE received_at < now() - interval '90 days'").update();
        log.info("Retention: {} KYC files, {} tickets, {} message copies, {} codes, {} reset tokens, {} webhook ids removed",
            kyc, tickets, messages, otps, resets, hooks);
    }

    private int deleteKycFiles() {
        record Doc(UUID id, String objectKey) {
        }
        List<Doc> docs = jdbc.sql("""
                SELECT d.id, d.object_key FROM genie_documents d
                  JOIN users u ON u.id = d.genie_id
                  JOIN genie_profiles gp ON gp.user_id = d.genie_id
                 WHERE (u.deleted_at IS NOT NULL AND u.deleted_at < now() - interval '30 days')
                    OR (gp.verification_status = 'REJECTED' AND NOT EXISTS (
                          SELECT 1 FROM genie_verification_events e
                           WHERE e.genie_id = d.genie_id AND e.created_at > now() - interval '180 days'))
                """).query(Doc.class).list();
        for (Doc d : docs) {
            try {
                storage.delete(d.objectKey());
            } catch (RuntimeException ex) {
                log.warn("Could not delete KYC file {}: {}", d.objectKey(), ex.getMessage());
            }
            jdbc.sql("DELETE FROM genie_documents WHERE id = :id").param("id", d.id()).update();
        }
        return docs.size();
    }

    private int deleteOldTickets() {
        List<UUID> old = jdbc.sql("""
                SELECT id FROM tickets WHERE status = 'CLOSED' AND closed_at < now() - interval '3 years' LIMIT 500
                """).query(UUID.class).list();
        for (UUID id : old) {
            jdbc.sql("SELECT file_key FROM ticket_attachments WHERE ticket_id = :t").param("t", id).query(String.class).list()
                .forEach(key -> {
                    try {
                        storage.delete(key);
                    } catch (RuntimeException ex) {
                        log.warn("Could not delete ticket photo {}: {}", key, ex.getMessage());
                    }
                });
            jdbc.sql("UPDATE refunds SET ticket_id = NULL WHERE ticket_id = :t").param("t", id).update();
            jdbc.sql("UPDATE genie_strikes SET ticket_id = NULL WHERE ticket_id = :t").param("t", id).update();
            jdbc.sql("DELETE FROM tickets WHERE id = :t").param("t", id).update();
        }
        return old.size();
    }
}
