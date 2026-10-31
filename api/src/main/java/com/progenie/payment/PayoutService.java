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
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
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
        List<UUID> genies = jdbc.sql(QueryUtil.get(QueryConstants.GET_PAYOUT_GENIE_IDS))
            .query(UUID.class).list();
        int created = 0;
        for (UUID genieId : genies) {
            BigDecimal pending = jdbc.sql(QueryUtil.get(QueryConstants.GET_PAYOUT_PENDING_TOTAL))
                .param("g", genieId).query(BigDecimal.class).single();
            BigDecimal amount = wallets.balance(genieId).subtract(pending);
            if (amount.signum() <= 0) {
                continue;
            }
            created += jdbc.sql(QueryUtil.get(QueryConstants.INSERT_PAYOUT_CREATE))
                .param("g", genieId).param("start", start).param("end", end).param("amount", amount)
                .update();
        }
        log.info("Created {} payouts for the week {} to {}", created, start, end);
        return created;
    }

    @Transactional(readOnly = true)
    public PageResponse<PayoutDto> list(String status, int page, int size) {
        String statusFilter = StringUtils.hasText(status) ? status.trim().toUpperCase() : null;
        long total = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_PAYOUT_LIST)).param("status", statusFilter)
            .query(Long.class).single();
        List<PayoutDto> items = jdbc.sql(QueryUtil.get(QueryConstants.GET_PAYOUT_LIST))
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
        Row p = jdbc.sql(QueryUtil.get(QueryConstants.LOCK_PAYOUT_BY_ID))
            .param("id", payoutId).query(Row.class).optional()
            .orElseThrow(() -> ApiException.notFound("PAYOUT_NOT_FOUND", "Payout not found"));
        if (!"PENDING".equals(p.status())) {
            throw ApiException.unprocessable("INVALID_STATE", "This payout is " + p.status());
        }
        jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_PAYOUT_PAID))
            .param("ref", reference.trim()).param("id", payoutId).update();
        ledger.postExternal(LedgerPostings.payout(p.genieId(), p.amount(), payoutId, reference));
        PayoutDto paid = get(payoutId);
        log.info("Payout {} of ₹{} to Genie {} marked paid", payoutId, p.amount(), p.genieId());
        events.publishEvent(new PayoutPaid(payoutId, p.genieId(), p.amount(), reference.trim(), paid.periodStart(),
            paid.periodEnd()));
        return paid;
    }

    @Transactional
    public PayoutDto markFailed(UUID payoutId, String reason) {
        int rows = jdbc.sql(QueryUtil.get(QueryConstants.UPDATE_PAYOUT_FAILED))
            .param("reason", reason.trim()).param("id", payoutId).update();
        if (rows == 0) {
            throw ApiException.unprocessable("INVALID_STATE", "Only pending payouts can be marked failed");
        }
        log.warn("Payout {} marked failed", payoutId);
        return get(payoutId);
    }

    /** A Genie paid their commission dues (from cash jobs) to the platform. */
    @Transactional
    public void recordSettlement(UUID genieId, BigDecimal amount, String reference) {
        boolean exists = jdbc.sql(QueryUtil.get(QueryConstants.EXISTS_PAYOUT_GENIE))
            .param("g", genieId).query(Boolean.class).single();
        if (!exists) {
            throw ApiException.notFound("GENIE_NOT_FOUND", "Genie not found");
        }
        ledger.postExternal(LedgerPostings.settlement(genieId, amount, reference));
    }

    private PayoutDto get(UUID payoutId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_PAYOUT_BY_ID)).param("id", payoutId).query(PayoutDto.class).single();
    }
}
