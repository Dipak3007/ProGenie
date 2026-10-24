package com.progenie.identity.privacy;

import java.util.List;
import java.util.UUID;

import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.storage.FileStorage;
import com.progenie.shared.util.QueryUtil;
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
        int messages = jdbc.sql(QueryUtil.get(QueryConstants.DELETE_RETENTION_OLD_MESSAGES)).update();
        int otps = jdbc.sql(QueryUtil.get(QueryConstants.DELETE_RETENTION_OLD_OTPS)).update();
        int resets = jdbc.sql(QueryUtil.get(QueryConstants.DELETE_RETENTION_OLD_RESET_TOKENS)).update();
        int hooks = jdbc.sql(QueryUtil.get(QueryConstants.DELETE_RETENTION_OLD_WEBHOOK_EVENTS)).update();
        log.info("Retention: {} KYC files, {} tickets, {} message copies, {} codes, {} reset tokens, {} webhook ids removed",
            kyc, tickets, messages, otps, resets, hooks);
    }

    private int deleteKycFiles() {
        record Doc(UUID id, String objectKey) {
        }
        List<Doc> docs = jdbc.sql(QueryUtil.get(QueryConstants.GET_RETENTION_EXPIRED_KYC_FILES)).query(Doc.class).list();
        for (Doc d : docs) {
            try {
                storage.delete(d.objectKey());
            } catch (RuntimeException ex) {
                log.warn("Could not delete KYC file {}: {}", d.objectKey(), ex.getMessage());
            }
            jdbc.sql(QueryUtil.get(QueryConstants.DELETE_GENIE_DOCUMENT_BY_ID)).param("id", d.id()).update();
        }
        return docs.size();
    }

    private int deleteOldTickets() {
        List<UUID> old = jdbc.sql(QueryUtil.get(QueryConstants.GET_RETENTION_OLD_CLOSED_TICKETS)).query(UUID.class).list();
        for (UUID id : old) {
            jdbc.sql(QueryUtil.get(QueryConstants.GET_TICKET_ATTACHMENT_FILE_KEYS)).param("t", id).query(String.class).list()
                .forEach(key -> {
                    try {
                        storage.delete(key);
                    } catch (RuntimeException ex) {
                        log.warn("Could not delete ticket photo {}: {}", key, ex.getMessage());
                    }
                });
            jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_RETENTION_DETACH_REFUNDS)).param("t", id).update();
            jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_RETENTION_DETACH_STRIKES)).param("t", id).update();
            jdbc.sql(QueryUtil.get(QueryConstants.DELETE_TICKET_BY_ID)).param("t", id).update();
        }
        return old.size();
    }
}
