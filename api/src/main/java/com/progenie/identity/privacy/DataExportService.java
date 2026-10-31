package com.progenie.identity.privacy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.progenie.messaging.NotificationService;
import com.progenie.messaging.delivery.Channel;
import com.progenie.messaging.delivery.MessageTemplate;
import com.progenie.messaging.delivery.Messenger;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.storage.FileStorage;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.Times;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Download my data" (DPDP right of access, design doc 16.5): a background job builds a ZIP of JSON files with
 * everything ProGenie holds about the user. The file can be downloaded only while logged in, for 7 days.
 */
@Service
public class DataExportService {

    private static final Logger log = LoggerFactory.getLogger(DataExportService.class);
    static final Duration LINK_LIFETIME = Duration.ofDays(7);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    public record ExportJobDto(UUID id, String status, @Nullable Long sizeBytes, OffsetDateTime requestedAt, @Nullable OffsetDateTime readyAt,
                               @Nullable OffsetDateTime expiresAt, @Nullable String error) {
    }

    private final JdbcClient jdbc;
    private final FileStorage storage;
    private final Messenger messenger;
    private final NotificationService notifications;
    private final JobLock lock;
    private final Clock clock;
    private final ZoneId zone;

    public DataExportService(JdbcClient jdbc, FileStorage storage, Messenger messenger, NotificationService notifications,
                             JobLock lock, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.messenger = messenger;
        this.notifications = notifications;
        this.lock = lock;
        this.clock = clock;
        this.zone = ZoneId.of(props.timezone());
    }

    /** Queues an export; a pending one is returned as is. One export per 24 hours. */
    @Transactional
    public ExportJobDto request(UUID userId) {
        jdbc.sql(QueryUtil.get(QueryConstants.LOCK_USER_BY_ID)).param("u", userId).query(UUID.class).single();
        List<ExportJobDto> recent = jdbc.sql(QueryUtil.get(QueryConstants.GET_EXPORT_RECENT))
            .param("u", userId).query(ExportJobDto.class).list();
        if (!recent.isEmpty()) {
            ExportJobDto last = recent.getFirst();
            if ("PENDING".equals(last.status())) {
                return last;
            }
            throw ApiException.tooManyRequests("EXPORT_RECENT", "You can download a copy of your data once a day. "
                + "Your latest copy is ready in Profile → Privacy").with("jobId", last.id());
        }
        UUID id = jdbc.sql(QueryUtil.get(QueryConstants.INSERT_EXPORT_REQUEST)).param("u", userId)
            .query(UUID.class).single();
        log.info("Data export {} requested by user {}", id, userId);
        return get(userId, id);
    }

    @Transactional(readOnly = true)
    public List<ExportJobDto> list(UUID userId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_EXPORT_LIST)).param("u", userId)
            .query(ExportJobDto.class).list();
    }

    public record ExportFile(String fileName, byte[] content) {
    }

    @Transactional(readOnly = true)
    public ExportFile download(UUID userId, UUID jobId) {
        record Row(String status, String fileKey, OffsetDateTime expiresAt, OffsetDateTime readyAt) {
        }
        Row r = jdbc.sql(QueryUtil.get(QueryConstants.GET_EXPORT_DOWNLOAD))
            .param("id", jobId).param("u", userId).query(Row.class).optional()
            .orElseThrow(() -> ApiException.notFound("EXPORT_NOT_FOUND", "Export not found"));
        if (!"READY".equals(r.status()) || r.expiresAt() == null || r.expiresAt().toInstant().isBefore(clock.instant())) {
            throw ApiException.unprocessable("EXPORT_NOT_AVAILABLE", "This export is not available (it may have expired)");
        }
        try (InputStream in = storage.get(r.fileKey())) {
            String date = r.readyAt().atZoneSameInstant(zone).toLocalDate().toString();
            return new ExportFile("progenie-data-" + date + ".zip", in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A short-lived link to a ready export (presigned URL on S3). */
    @Transactional(readOnly = true)
    public FileStorage.FileLink link(UUID userId, UUID jobId) {
        record Row(String status, String fileKey, OffsetDateTime expiresAt, OffsetDateTime readyAt) {
        }
        Row r = jdbc.sql(QueryUtil.get(QueryConstants.GET_EXPORT_DOWNLOAD))
            .param("id", jobId).param("u", userId).query(Row.class).optional()
            .orElseThrow(() -> ApiException.notFound("EXPORT_NOT_FOUND", "Export not found"));
        if (!"READY".equals(r.status()) || r.expiresAt() == null || r.expiresAt().toInstant().isBefore(clock.instant())) {
            throw ApiException.unprocessable("EXPORT_NOT_AVAILABLE", "This export is not available (it may have expired)");
        }
        String date = r.readyAt().atZoneSameInstant(zone).toLocalDate().toString();
        return storage.link(r.fileKey(), "progenie-data-" + date + ".zip", "application/zip", false);
    }

    // ------------------------------------------------------------------ background job

    @Scheduled(fixedDelayString = "${progenie.privacy.export-interval:20s}", initialDelayString = "15s")
    @Transactional
    public void buildPendingExports() {
        if (!lock.tryAcquire("data-export")) {
            return;
        }
        record Job(UUID id, UUID userId) {
        }
        List<Job> jobs = jdbc.sql(QueryUtil.get(QueryConstants.LOCK_EXPORT_PENDING)).query(Job.class).list();
        for (Job job : jobs) {
            try {
                byte[] zip = buildZip(job.userId());
                String key = "exports/" + job.userId() + "/" + job.id() + ".zip";
                storage.put(key, new ByteArrayInputStream(zip), zip.length, "application/zip");
                Instant expires = clock.instant().plus(LINK_LIFETIME);
                jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_EXPORT_READY))
                    .param("k", key).param("size", zip.length).param("exp", Times.odt(expires)).param("id", job.id())
                    .update();
                String expiresText = DATE.format(expires.atZone(zone));
                boolean hasEmail = jdbc.sql(QueryUtil.get(QueryConstants.GET_USER_HAS_VERIFIED_EMAIL))
                    .param("u", job.userId()).query(Boolean.class).single();
                String role = jdbc.sql(QueryUtil.get(QueryConstants.GET_USER_ROLE)).param("u", job.userId()).query(String.class).single();
                String link = "GENIE".equals(role) ? "/genie/privacy" : "/account/privacy";
                messenger.toUser(job.userId(), MessageTemplate.DATA_EXPORT_READY,
                    Set.of(hasEmail ? Channel.EMAIL : Channel.SMS),
                    Map.of("expires", expiresText, "link", link, "linkLabel", "Download my data"),
                    "export:" + job.id());
                notifications.notify(job.userId(), "DATA_EXPORT_READY", "Your data is ready to download",
                    "Download it from Settings & privacy before " + expiresText + ".", link);
                log.info("Data export {} ready ({} bytes)", job.id(), zip.length);
            } catch (RuntimeException ex) {
                log.error("Data export {} failed", job.id(), ex);
                jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_EXPORT_FAILED))
                    .param("e", String.valueOf(ex.getMessage()).substring(0, Math.min(300, String.valueOf(ex.getMessage()).length())))
                    .param("id", job.id()).update();
            }
        }
    }

    /** Expired export files are deleted. */
    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT3M")
    @Transactional
    public void expireOldExports() {
        if (!lock.tryAcquire("data-export-expiry")) {
            return;
        }
        record Old(UUID id, String fileKey) {
        }
        List<Old> old = jdbc.sql(QueryUtil.get(QueryConstants.GET_EXPORT_EXPIRED))
            .query(Old.class).list();
        for (Old o : old) {
            if (o.fileKey() != null) {
                storage.delete(o.fileKey());
            }
            jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_EXPORT_EXPIRE)).param("id", o.id()).update();
        }
    }

    // ------------------------------------------------------------------ the ZIP

    byte[] buildZip(UUID userId) {
        String role = jdbc.sql(QueryUtil.get(QueryConstants.GET_USER_ROLE)).param("u", userId).query(String.class).single();
        Map<String, String> files = new LinkedHashMap<>();
        files.put("profile.json", one(QueryConstants.FRAGMENT_EXPORT_PROFILE, userId));
        files.put("addresses.json", many(QueryConstants.FRAGMENT_EXPORT_ADDRESSES, userId));
        files.put("favourites.json", many(QueryConstants.FRAGMENT_EXPORT_FAVOURITES, userId));
        files.put("bookings.json", many(QueryConstants.FRAGMENT_EXPORT_BOOKINGS, userId));
        files.put("payments.json", many(QueryConstants.FRAGMENT_EXPORT_PAYMENTS, userId));
        files.put("receipts.json", many(QueryConstants.FRAGMENT_EXPORT_RECEIPTS, userId));
        files.put("reviews-written.json", many(QueryConstants.FRAGMENT_EXPORT_REVIEWS_WRITTEN, userId));
        files.put("tickets.json", many(QueryConstants.FRAGMENT_EXPORT_TICKETS, userId));
        files.put("consents.json", many(QueryConstants.FRAGMENT_EXPORT_CONSENTS, userId));
        files.put("notifications.json", many(QueryConstants.FRAGMENT_EXPORT_NOTIFICATIONS, userId));
        files.put("notification-preferences.json", many(QueryConstants.FRAGMENT_EXPORT_NOTIFICATION_PREFERENCES, userId));
        if ("GENIE".equals(role)) {
            files.put("genie/profile.json", one(QueryConstants.FRAGMENT_EXPORT_GENIE_PROFILE, userId));
            files.put("genie/documents.json", many(QueryConstants.FRAGMENT_EXPORT_GENIE_DOCUMENTS, userId));
            files.put("genie/jobs.json", many(QueryConstants.FRAGMENT_EXPORT_GENIE_JOBS, userId));
            files.put("genie/earnings-ledger.json", many(QueryConstants.FRAGMENT_EXPORT_GENIE_EARNINGS_LEDGER, userId));
            files.put("genie/payouts.json", many(QueryConstants.FRAGMENT_EXPORT_GENIE_PAYOUTS, userId));
            files.put("genie/reviews-received.json", many(QueryConstants.FRAGMENT_EXPORT_GENIE_REVIEWS_RECEIVED, userId));
        }
        files.put("README.txt", """
            ProGenie: a copy of your personal data
            ======================================
            Created %s. Each .json file holds one kind of data (dates are in UTC).

            profile.json               your account
            addresses.json             saved addresses
            bookings.json              bookings you made (with the address as it was at booking time)
            payments.json              payments and refunds
            receipts.json              receipt and credit-note numbers (PDFs are in the app)
            tickets.json               reports and privacy requests you raised, with the replies
            consents.json              which policy versions you accepted, when and from where
            notifications.json         in-app notifications
            genie/...                  (Genies only) profile, documents (masked), jobs, earnings and payouts

            Questions or corrections: write to the grievance officer listed in the Privacy Policy.
            """.formatted(DateTimeFormatter.ISO_INSTANT.format(clock.instant())));
        return zip(files);
    }

    /** {@code queryKey}: a fragment.export.* key whose query returns one JSON value. */
    private String one(String queryKey, UUID userId) {
        String v = jdbc.sql(QueryUtil.get(QueryConstants.GET_EXPORT_AS_JSON_OBJECT, Map.of("query", queryKey)))
            .param("u", userId).query(String.class).optional().orElse(null);
        return v == null ? "null" : v;
    }

    /** {@code queryKey}: a fragment.export.* key whose query returns one JSON value per row. */
    private String many(String queryKey, UUID userId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_EXPORT_AS_JSON_ARRAY, Map.of("query", queryKey)))
            .param("u", userId).query(String.class).single();
    }

    private static byte[] zip(Map<String, String> files) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private ExportJobDto get(UUID userId, UUID id) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_EXPORT_JOB)).param("id", id).param("u", userId)
            .query(ExportJobDto.class).single();
    }
}
