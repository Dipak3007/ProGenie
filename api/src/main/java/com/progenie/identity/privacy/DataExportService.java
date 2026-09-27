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

import com.progenie.notification.NotificationService;
import com.progenie.notification.delivery.Channel;
import com.progenie.notification.delivery.MessageTemplate;
import com.progenie.notification.delivery.Messenger;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.storage.FileStorage;
import com.progenie.shared.util.Times;
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

    public record ExportJobDto(UUID id, String status, Long sizeBytes, OffsetDateTime requestedAt, OffsetDateTime readyAt,
                               OffsetDateTime expiresAt, String error) {
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
        jdbc.sql("SELECT id FROM users WHERE id = :u FOR UPDATE").param("u", userId).query(UUID.class).single();
        List<ExportJobDto> recent = jdbc.sql(SELECT + " WHERE user_id = :u AND requested_at > now() - interval '24 hours'"
                + " AND status IN ('PENDING', 'READY') ORDER BY requested_at DESC")
            .param("u", userId).query(ExportJobDto.class).list();
        if (!recent.isEmpty()) {
            ExportJobDto last = recent.getFirst();
            if ("PENDING".equals(last.status())) {
                return last;
            }
            throw ApiException.tooManyRequests("EXPORT_RECENT", "You can download a copy of your data once a day. "
                + "Your latest copy is ready in Profile → Privacy").with("jobId", last.id());
        }
        UUID id = jdbc.sql("INSERT INTO data_export_jobs (user_id) VALUES (:u) RETURNING id").param("u", userId)
            .query(UUID.class).single();
        return get(userId, id);
    }

    @Transactional(readOnly = true)
    public List<ExportJobDto> list(UUID userId) {
        return jdbc.sql(SELECT + " WHERE user_id = :u ORDER BY requested_at DESC LIMIT 10").param("u", userId)
            .query(ExportJobDto.class).list();
    }

    public record ExportFile(String fileName, byte[] content) {
    }

    @Transactional(readOnly = true)
    public ExportFile download(UUID userId, UUID jobId) {
        record Row(String status, String fileKey, OffsetDateTime expiresAt, OffsetDateTime readyAt) {
        }
        Row r = jdbc.sql("SELECT status, file_key, expires_at, ready_at FROM data_export_jobs WHERE id = :id AND user_id = :u")
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

    // ------------------------------------------------------------------ background job

    @Scheduled(fixedDelayString = "${progenie.privacy.export-interval:20s}", initialDelayString = "15s")
    @Transactional
    public void buildPendingExports() {
        if (!lock.tryAcquire("data-export")) {
            return;
        }
        record Job(UUID id, UUID userId) {
        }
        List<Job> jobs = jdbc.sql("""
                SELECT id, user_id FROM data_export_jobs WHERE status = 'PENDING' ORDER BY requested_at LIMIT 5
                FOR UPDATE SKIP LOCKED
                """).query(Job.class).list();
        for (Job job : jobs) {
            try {
                byte[] zip = buildZip(job.userId());
                String key = "exports/" + job.userId() + "/" + job.id() + ".zip";
                storage.put(key, new ByteArrayInputStream(zip), zip.length, "application/zip");
                Instant expires = clock.instant().plus(LINK_LIFETIME);
                jdbc.sql("""
                        UPDATE data_export_jobs SET status = 'READY', file_key = :k, size_bytes = :size, ready_at = now(),
                               expires_at = :exp WHERE id = :id
                        """)
                    .param("k", key).param("size", zip.length).param("exp", Times.odt(expires)).param("id", job.id())
                    .update();
                String expiresText = DATE.format(expires.atZone(zone));
                boolean hasEmail = jdbc.sql("SELECT email IS NOT NULL AND email_verified FROM users WHERE id = :u")
                    .param("u", job.userId()).query(Boolean.class).single();
                String role = jdbc.sql("SELECT role FROM users WHERE id = :u").param("u", job.userId()).query(String.class).single();
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
                jdbc.sql("UPDATE data_export_jobs SET status = 'FAILED', error = :e WHERE id = :id")
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
        List<Old> old = jdbc.sql("SELECT id, file_key FROM data_export_jobs WHERE status = 'READY' AND expires_at < now()")
            .query(Old.class).list();
        for (Old o : old) {
            if (o.fileKey() != null) {
                storage.delete(o.fileKey());
            }
            jdbc.sql("UPDATE data_export_jobs SET status = 'EXPIRED', file_key = NULL WHERE id = :id").param("id", o.id()).update();
        }
    }

    // ------------------------------------------------------------------ the ZIP

    byte[] buildZip(UUID userId) {
        String role = jdbc.sql("SELECT role FROM users WHERE id = :u").param("u", userId).query(String.class).single();
        Map<String, String> files = new LinkedHashMap<>();
        files.put("profile.json", one("SELECT to_jsonb(u) - 'password_hash' - 'version' FROM users u WHERE u.id = :u", userId));
        files.put("addresses.json", many("""
                SELECT to_jsonb(a) - 'location' || jsonb_build_object('lat', ST_Y(a.location::geometry), 'lng', ST_X(a.location::geometry))
                  FROM addresses a WHERE a.user_id = :u""", userId));
        files.put("favourites.json", many("""
                SELECT jsonb_build_object('genie', g.full_name, 'addedAt', f.created_at)
                  FROM favourites f JOIN users g ON g.id = f.genie_id WHERE f.customer_id = :u""", userId));
        files.put("bookings.json", many("""
                SELECT to_jsonb(b) - 'start_otp_hash' - 'customer_location' - 'idempotency_key' - 'commission_rate'
                       - 'commission_amount' - 'genie_payout' - 'version'
                       || jsonb_build_object('service', s.name, 'genie', g.full_name)
                  FROM bookings b JOIN services s ON s.id = b.service_id JOIN users g ON g.id = b.genie_id
                 WHERE b.customer_id = :u ORDER BY b.slot_start""", userId));
        files.put("payments.json", many("""
                SELECT to_jsonb(p) || jsonb_build_object('refunds', coalesce((SELECT jsonb_agg(jsonb_build_object(
                           'amount', r.amount, 'status', r.status, 'reason', r.reason, 'processedAt', r.processed_at))
                         FROM refunds r WHERE r.payment_id = p.id), '[]'::jsonb))
                  FROM payments p WHERE p.customer_id = :u ORDER BY p.created_at""", userId));
        files.put("receipts.json", many("""
                SELECT jsonb_build_object('number', number, 'kind', kind, 'amount', amount, 'issuedAt', issued_at)
                  FROM receipts WHERE customer_id = :u ORDER BY issued_at""", userId));
        files.put("reviews-written.json", many("""
                SELECT jsonb_build_object('rating', r.rating, 'comment', r.comment, 'genie', g.full_name,
                                          'genieReply', r.genie_reply, 'createdAt', r.created_at)
                  FROM reviews r JOIN users g ON g.id = r.genie_id WHERE r.customer_id = :u""", userId));
        files.put("tickets.json", many("""
                SELECT jsonb_build_object('ref', t.ticket_ref, 'type', t.type, 'category', t.category, 'status', t.status,
                         'subject', t.subject, 'description', t.description, 'resolution', t.resolution_note,
                         'createdAt', t.created_at,
                         'messages', coalesce((SELECT jsonb_agg(jsonb_build_object('from', m.author_role, 'body', m.body,
                                                  'at', m.created_at) ORDER BY m.created_at)
                                              FROM ticket_messages m WHERE m.ticket_id = t.id AND NOT m.internal), '[]'::jsonb))
                  FROM tickets t WHERE t.raised_by = :u ORDER BY t.created_at""", userId));
        files.put("consents.json", many("""
                SELECT jsonb_build_object('document', c.kind, 'version', c.version, 'acceptedAt', c.accepted_at,
                                          'ip', c.ip, 'browser', c.user_agent)
                  FROM user_consents c WHERE c.user_id = :u ORDER BY c.accepted_at""", userId));
        files.put("notifications.json", many("""
                SELECT jsonb_build_object('title', title, 'body', body, 'createdAt', created_at, 'readAt', read_at)
                  FROM notifications WHERE user_id = :u ORDER BY created_at""", userId));
        files.put("notification-preferences.json", many(
            "SELECT to_jsonb(p) - 'user_id' FROM notification_preferences p WHERE p.user_id = :u", userId));
        if ("GENIE".equals(role)) {
            files.put("genie/profile.json", one("""
                    SELECT to_jsonb(gp) - 'base_location'
                           || jsonb_build_object('baseLat', ST_Y(gp.base_location::geometry), 'baseLng', ST_X(gp.base_location::geometry))
                      FROM genie_profiles gp WHERE gp.user_id = :u""", userId));
            files.put("genie/documents.json", many("""
                    SELECT jsonb_build_object('type', doc_type, 'maskedNumber', masked_number, 'status', status,
                                              'uploadedAt', created_at)
                      FROM genie_documents WHERE genie_id = :u""", userId));
            files.put("genie/jobs.json", many("""
                    SELECT jsonb_build_object('ref', b.booking_ref, 'service', s.name, 'slotStart', b.slot_start,
                             'status', b.status, 'serviceAmount', b.service_amount, 'travelFee', b.travel_fee,
                             'extraAmount', b.extra_amount, 'tip', b.tip_amount, 'commission', b.commission_amount,
                             'yourEarnings', b.genie_payout, 'area', b.address_snapshot ->> 'area')
                      FROM bookings b JOIN services s ON s.id = b.service_id WHERE b.genie_id = :u ORDER BY b.slot_start""", userId));
            files.put("genie/earnings-ledger.json", many("""
                    SELECT jsonb_build_object('account', account, 'direction', direction, 'amount', amount,
                                              'description', description, 'at', created_at)
                      FROM ledger_entries WHERE genie_id = :u ORDER BY created_at""", userId));
            files.put("genie/payouts.json", many("SELECT to_jsonb(p) FROM payouts p WHERE p.genie_id = :u ORDER BY p.created_at", userId));
            files.put("genie/reviews-received.json", many("""
                    SELECT jsonb_build_object('rating', rating, 'comment', comment, 'yourReply', genie_reply, 'createdAt', created_at)
                      FROM reviews WHERE genie_id = :u ORDER BY created_at""", userId));
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

    private String one(String sql, UUID userId) {
        String v = jdbc.sql("SELECT (" + sql + ")::text").param("u", userId).query(String.class).optional().orElse(null);
        return v == null ? "null" : v;
    }

    private String many(String sql, UUID userId) {
        return jdbc.sql("SELECT coalesce(jsonb_agg(x.j), '[]'::jsonb)::text FROM (" + sql.replaceFirst("(?s)^\\s*SELECT", "SELECT") + ") AS x(j)")
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

    private static final String SELECT = """
        SELECT id, status, size_bytes, requested_at, ready_at, expires_at, error FROM data_export_jobs
        """;

    private ExportJobDto get(UUID userId, UUID id) {
        return jdbc.sql(SELECT + " WHERE id = :id AND user_id = :u").param("id", id).param("u", userId)
            .query(ExportJobDto.class).single();
    }
}
