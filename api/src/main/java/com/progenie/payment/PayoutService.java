package com.progenie.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import com.progenie.payment.PaymentDtos.PayoutDto;
import com.progenie.payment.PaymentDtos.PayoutPaid;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.web.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Weekly Genie payouts (Monday for the week ending Sunday). A payout is created for every Genie
 * whose wallet is positive; an admin transfers the money (UPI) and marks it paid, which posts a
 * PAYOUT debit. Genies with commission dues settle them with the admin (SETTLEMENT credit).
 */
@Service
public class PayoutService {

    private static final Logger log = LoggerFactory.getLogger(PayoutService.class);

    static final String PAYOUT_SELECT = """
        SELECT p.id, p.genie_id, u.full_name AS genie_name, gp.payout_upi_id AS upi_id, p.period_start, p.period_end,
               p.amount, p.status, p.reference, p.created_at, p.paid_at
          FROM payouts p
          JOIN users u ON u.id = p.genie_id
          JOIN genie_profiles gp ON gp.user_id = p.genie_id
        """;

    private final JdbcClient jdbc;
    private final LedgerService ledger;
    private final WalletService wallets;
    private final ZoneId zone;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    public PayoutService(JdbcClient jdbc, LedgerService ledger, WalletService wallets, AppProperties props, Clock clock,
                         ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.events = events;
        this.ledger = ledger;
        this.wallets = wallets;
        this.zone = ZoneId.of(props.timezone());
        this.clock = clock;
    }

    /** The Sunday that ended the last full week. */
    public LocalDate lastWeekEnd() {
        return LocalDate.now(clock.withZone(zone)).with(TemporalAdjusters.previous(DayOfWeek.SUNDAY));
    }

    /** Creates one PENDING payout per Genie with money to receive for the week ending {@code periodEnd}. */
    @Transactional
    public int generate(LocalDate periodEnd) {
        LocalDate end = periodEnd == null ? lastWeekEnd() : periodEnd;
        LocalDate start = end.minusDays(6);
        List<UUID> genies = jdbc.sql("SELECT DISTINCT genie_id FROM ledger_entries WHERE genie_id IS NOT NULL")
            .query(UUID.class).list();
        int created = 0;
        for (UUID genieId : genies) {
            BigDecimal pending = jdbc.sql("SELECT coalesce(sum(amount), 0) FROM payouts WHERE genie_id = :g AND status = 'PENDING'")
                .param("g", genieId).query(BigDecimal.class).single();
            BigDecimal amount = wallets.balance(genieId).subtract(pending);
            if (amount.signum() <= 0) {
                continue;
            }
            created += jdbc.sql("""
                    INSERT INTO payouts (genie_id, period_start, period_end, amount, status)
                    VALUES (:g, :start, :end, :amount, 'PENDING')
                    ON CONFLICT (genie_id, period_start, period_end) DO NOTHING
                    """)
                .param("g", genieId).param("start", start).param("end", end).param("amount", amount)
                .update();
        }
        log.info("Created {} payouts for the week {} to {}", created, start, end);
        return created;
    }

    @Transactional(readOnly = true)
    public PageResponse<PayoutDto> list(String status, int page, int size) {
        String statusFilter = StringUtils.hasText(status) ? status.trim().toUpperCase() : null;
        String where = " WHERE (CAST(:status AS varchar) IS NULL OR p.status = :status)";
        long total = jdbc.sql("SELECT count(*) FROM payouts p" + where).param("status", statusFilter)
            .query(Long.class).single();
        List<PayoutDto> items = jdbc.sql(PAYOUT_SELECT + where + " ORDER BY p.created_at DESC LIMIT :limit OFFSET :offset")
            .param("status", statusFilter)
            .param("limit", PageResponse.clampSize(size)).param("offset", PageResponse.offset(page, size))
            .query(PayoutDto.class).list();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    /** Admin sent the money (e.g. UPI transfer); {@code reference} is the bank/UPI reference. */
    @Transactional
    public PayoutDto markPaid(UUID payoutId, String reference) {
        record Row(UUID genieId, BigDecimal amount, String status) {
        }
        Row p = jdbc.sql("SELECT genie_id, amount, status FROM payouts WHERE id = :id FOR UPDATE")
            .param("id", payoutId).query(Row.class).optional()
            .orElseThrow(() -> ApiException.notFound("PAYOUT_NOT_FOUND", "Payout not found"));
        if (!"PENDING".equals(p.status())) {
            throw ApiException.unprocessable("INVALID_STATE", "This payout is " + p.status());
        }
        jdbc.sql("UPDATE payouts SET status = 'PAID', reference = :ref, paid_at = now() WHERE id = :id")
            .param("ref", reference.trim()).param("id", payoutId).update();
        ledger.postExternal(LedgerPostings.payout(p.genieId(), p.amount(), payoutId, reference));
        PayoutDto paid = get(payoutId);
        events.publishEvent(new PayoutPaid(payoutId, p.genieId(), p.amount(), reference.trim(), paid.periodStart(),
            paid.periodEnd()));
        return paid;
    }

    @Transactional
    public PayoutDto markFailed(UUID payoutId, String reason) {
        int rows = jdbc.sql("UPDATE payouts SET status = 'FAILED', reference = :reason WHERE id = :id AND status = 'PENDING'")
            .param("reason", reason.trim()).param("id", payoutId).update();
        if (rows == 0) {
            throw ApiException.unprocessable("INVALID_STATE", "Only pending payouts can be marked failed");
        }
        return get(payoutId);
    }

    /** A Genie paid their commission dues (from cash jobs) to the platform. */
    @Transactional
    public void recordSettlement(UUID genieId, BigDecimal amount, String reference) {
        boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM genie_profiles WHERE user_id = :g)")
            .param("g", genieId).query(Boolean.class).single();
        if (!exists) {
            throw ApiException.notFound("GENIE_NOT_FOUND", "Genie not found");
        }
        ledger.postExternal(LedgerPostings.settlement(genieId, amount, reference));
    }

    private PayoutDto get(UUID payoutId) {
        return jdbc.sql(PAYOUT_SELECT + " WHERE p.id = :id").param("id", payoutId).query(PayoutDto.class).single();
    }
}
