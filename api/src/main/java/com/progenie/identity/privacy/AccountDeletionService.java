package com.progenie.identity.privacy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.progenie.identity.domain.User;
import com.progenie.identity.repository.RefreshTokenRepository;
import com.progenie.identity.repository.UserRepository;
import com.progenie.identity.service.OtpService;
import com.progenie.notification.delivery.Channel;
import com.progenie.notification.delivery.MessageTemplate;
import com.progenie.notification.delivery.Messenger;
import com.progenie.notification.delivery.NotificationPreferences;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.jobs.JobLock;
import com.progenie.shared.storage.FileStorage;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.Times;
import com.progenie.shared.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Account deletion (DPDP right to erasure, design doc 16.5). Refused while something is still open; otherwise it
 * runs after a 7-day grace period in which logging in shows "Cancel deletion". Deletion anonymises the account:
 * personal details go, while bookings, payments, receipts and the ledger stay (tax law), with the address reduced
 * to area, city and pincode.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);
    static final Duration GRACE = Duration.ofDays(7);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** Something that must be settled first; {@code code} lets the app link to the right screen. */
    public record Blocker(String code, String message) {
    }

    public record DeletionStatus(UUID requestId, String status, OffsetDateTime requestedAt, OffsetDateTime scheduledFor,
                                 List<Blocker> blockers) {
    }

    public record DeletionRequestRow(UUID id, UUID userId, String fullName, String role, String status, String reason,
                                     OffsetDateTime requestedAt, OffsetDateTime scheduledFor, OffsetDateTime completedAt,
                                     String note) {
    }

    private final JdbcClient jdbc;
    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final NotificationPreferences preferences;
    private final OtpService otps;
    private final FileStorage storage;
    private final Messenger messenger;
    private final JobLock lock;
    private final Clock clock;
    private final ZoneId zone;

    public AccountDeletionService(JdbcClient jdbc, UserRepository users, RefreshTokenRepository refreshTokens,
                                  PasswordEncoder passwordEncoder, NotificationPreferences preferences, OtpService otps,
                                  FileStorage storage, Messenger messenger, JobLock lock, Clock clock, AppProperties props) {
        this.jdbc = jdbc;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.preferences = preferences;
        this.otps = otps;
        this.storage = storage;
        this.messenger = messenger;
        this.lock = lock;
        this.clock = clock;
        this.zone = ZoneId.of(props.timezone());
    }

    /** The current request (if any) and what would block a deletion right now. */
    @Transactional(readOnly = true)
    public DeletionStatus status(UUID userId) {
        record Req(UUID id, String status, OffsetDateTime requestedAt, OffsetDateTime scheduledFor) {
        }
        Req r = jdbc.sql(QueryUtil.get(QueryConstants.GET_DELETION_SCHEDULED))
            .param("u", userId).query(Req.class).optional().orElse(null);
        return new DeletionStatus(r == null ? null : r.id(), r == null ? "NONE" : r.status(),
            r == null ? null : r.requestedAt(), r == null ? null : r.scheduledFor(), blockers(userId));
    }

    @Transactional
    public DeletionStatus request(UUID userId, String reason) {
        User user = users.findById(userId).orElseThrow(() -> ApiException.notFound("USER_NOT_FOUND", "User not found"));
        if ("ADMIN".equals(user.getRole().name())) {
            throw ApiException.unprocessable("ADMIN_ACCOUNT", "Admin accounts are removed by another admin");
        }
        List<Blocker> blockers = blockers(userId);
        if (!blockers.isEmpty()) {
            throw ApiException.unprocessable("DELETION_BLOCKED", "Please settle these first: "
                    + String.join("; ", blockers.stream().map(Blocker::message).toList()))
                .with("blockers", blockers);
        }
        boolean exists = jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_DELETION_SCHEDULED))
            .param("u", userId).query(Boolean.class).single();
        if (exists) {
            return status(userId);
        }
        Instant when = clock.instant().plus(GRACE);
        String why = StringUtils.hasText(reason) ? reason.trim() : null;
        UUID id = jdbc.sql(QueryUtil.get(QueryConstants.INSERT_DELETION_REQUEST))
            .param("u", userId).param("r", why == null ? null : why.length() > 300 ? why.substring(0, 300) : why)
            .param("when", Times.odt(when)).query(UUID.class).single();
        messenger.toUser(userId, MessageTemplate.ACCOUNT_DELETION_SCHEDULED, Set.of(Channel.SMS, Channel.EMAIL),
            Map.of("date", DATE.format(when.atZone(zone)), "link", "/login", "linkLabel", "Keep my account"),
            "deletion:" + id);
        log.info("Account deletion scheduled for {} on {}", userId, when);
        return status(userId);
    }

    @Transactional
    public void cancel(UUID userId) {
        int rows = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_DELETION_CANCEL))
            .param("u", userId).update();
        if (rows == 0) {
            throw ApiException.notFound("NO_DELETION_REQUEST", "There is no pending deletion");
        }
        log.info("Account deletion cancelled by {}", userId);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeletionRequestRow> adminList(String status, int page, int size) {
        String s = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : null;
        long total = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_DELETION_ADMIN_LIST)).param("s", s).query(Long.class).single();
        List<DeletionRequestRow> items = jdbc.sql(QueryUtil.get(QueryConstants.GET_DELETION_ADMIN_LIST))
            .param("s", s).param("limit", PageResponse.clampSize(size)).param("offset", PageResponse.offset(page, size))
            .query(DeletionRequestRow.class).list();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    List<Blocker> blockers(UUID userId) {
        List<Blocker> out = new ArrayList<>();
        long upcoming = count(QueryConstants.COUNT_DELETION_UPCOMING_BOOKINGS, userId);
        if (upcoming > 0) {
            out.add(new Blocker("UPCOMING_BOOKINGS", upcoming + " booking" + (upcoming == 1 ? " is" : "s are") + " still upcoming"));
        }
        long tickets = count(QueryConstants.COUNT_DELETION_OPEN_TICKETS, userId);
        if (tickets > 0) {
            out.add(new Blocker("OPEN_TICKETS", tickets + " reported problem" + (tickets == 1 ? " is" : "s are") + " still open"));
        }
        long unpaid = count(QueryConstants.COUNT_DELETION_UNPAID, userId);
        if (unpaid > 0) {
            out.add(new Blocker("UNPAID", "you have " + unpaid + " unpaid amount" + (unpaid == 1 ? "" : "s")));
        }
        BigDecimal wallet = jdbc.sql(QueryUtil.get(QueryConstants.GET_DELETION_GENIE_BALANCE))
            .param("u", userId).query(BigDecimal.class).single();
        if (wallet.signum() != 0) {
            out.add(new Blocker("WALLET_NOT_SETTLED", wallet.signum() > 0
                ? "ProGenie still owes you ₹" + wallet + " (it is paid in the next weekly payout)"
                : "you owe ₹" + wallet.negate() + " in commission; settle it with ProGenie"));
        }
        long payouts = count(QueryConstants.COUNT_DELETION_PENDING_PAYOUTS, userId);
        if (payouts > 0) {
            out.add(new Blocker("PAYOUT_PENDING", "a payout to you is still being processed"));
        }
        return out;
    }

    private long count(String queryKey, UUID userId) {
        return jdbc.sql(QueryUtil.get(queryKey)).param("u", userId).query(Long.class).single();
    }

    // ------------------------------------------------------------------ the job

    @Scheduled(fixedDelayString = "${progenie.privacy.deletion-interval:PT1H}", initialDelayString = "${progenie.privacy.deletion-initial-delay:PT4M}")
    @Transactional
    public void runDueDeletions() {
        if (!lock.tryAcquire("account-deletion")) {
            return;
        }
        record Due(UUID id, UUID userId) {
        }
        List<Due> due = jdbc.sql(QueryUtil.get(QueryConstants.LOCK_DELETION_DUE)).query(Due.class).list();
        for (Due d : due) {
            List<Blocker> blockers = blockers(d.userId());
            if (!blockers.isEmpty()) {
                String note = String.join("; ", blockers.stream().map(Blocker::message).toList());
                jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_DELETION_BLOCKED))
                    .param("n", note.length() > 300 ? note.substring(0, 300) : note).param("id", d.id()).update();
                log.warn("Deletion of {} blocked at run time: {}", d.userId(), note);
                continue;
            }
            anonymise(d.userId());
            jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_DELETION_DONE))
                .param("id", d.id()).update();
            log.info("Account {} deleted (anonymised)", d.userId());
        }
    }

    /** Removes personal data; see the retention table in the design doc. */
    void anonymise(UUID userId) {
        Instant now = clock.instant();
        User user = users.findById(userId).orElseThrow();
        user.anonymise(passwordEncoder.encode(UUID.randomUUID().toString()), now);
        users.flush();
        refreshTokens.revokeAllForUser(userId, null, now);
        preferences.deleteAll(userId);
        otps.deleteAllFor(userId);
        List<String> exportFiles = jdbc.sql(QueryUtil.get(QueryConstants.GET_DELETION_EXPORT_FILE_KEYS))
            .param("u", userId).query(String.class).list();
        exportFiles.forEach(storage::delete);
        for (String queryKey : List.of(
            QueryConstants.DELETE_DELETION_EXPORT_JOBS,
            QueryConstants.DELETE_DELETION_RESET_TOKENS,
            QueryConstants.DELETE_DELETION_FAVOURITES,
            QueryConstants.DELETE_DELETION_NOTIFICATIONS,
            QueryConstants.DELETE_DELETION_ADDRESSES,
            QueryConstants.UPDATE_DELETION_REDACT_MESSAGES,
            QueryConstants.UPDATE_DELETION_TRIM_BOOKING_ADDRESSES,
            // a Genie leaves the directory; KYC files are removed 30 days later by the retention job
            QueryConstants.UPDATE_DELETION_RETIRE_GENIE_PROFILE,
            QueryConstants.DELETE_DELETION_GENIE_LOCATION)) {
            jdbc.sql(QueryUtil.get(queryKey)).param("u", userId).update();
        }
    }
}
