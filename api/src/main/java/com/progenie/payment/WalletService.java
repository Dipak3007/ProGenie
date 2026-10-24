package com.progenie.payment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;

import com.progenie.payment.PaymentDtos.LedgerLineDto;
import com.progenie.payment.PaymentDtos.PayoutDto;
import com.progenie.payment.PaymentDtos.WalletDto;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.Times;
import com.progenie.shared.web.PageResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A Genie's wallet, computed from the ledger (never stored, so it can never drift). */
@Service
public class WalletService {


    private final JdbcClient jdbc;
    private final ZoneId zone;
    private final Clock clock;
    private final String currency;

    public WalletService(JdbcClient jdbc, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.zone = ZoneId.of(props.timezone());
        this.clock = clock;
        this.currency = props.payments().currency();
    }

    /** Positive: the platform owes the Genie. Negative: the Genie owes commission from cash jobs. */
    @Transactional(readOnly = true)
    public BigDecimal balance(UUID genieId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_WALLET_BALANCE))
            .param("g", genieId).query(BigDecimal.class).single();
    }

    @Transactional(readOnly = true)
    public WalletDto wallet(UUID genieId) {
        BigDecimal balance = balance(genieId);
        BigDecimal pending = jdbc.sql(QueryUtil.get(QueryConstants.GET_PAYOUT_PENDING_TOTAL))
            .param("g", genieId).query(BigDecimal.class).single();
        Instant weekStart = LocalDate.now(clock.withZone(zone)).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(zone).toInstant();
        BigDecimal weekEarnings = jdbc.sql(QueryUtil.get(QueryConstants.GET_WALLET_EARNINGS_SINCE))
            .param("g", genieId).param("since", Times.odt(weekStart)).query(BigDecimal.class).single();
        int weekJobs = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_WALLET_COMPLETED_JOBS_SINCE))
            .param("g", genieId).param("since", Times.odt(weekStart)).query(Integer.class).single();
        BigDecimal lifetime = jdbc.sql(QueryUtil.get(QueryConstants.GET_WALLET_LIFETIME_EARNINGS))
            .param("g", genieId).query(BigDecimal.class).single();
        BigDecimal available = balance.subtract(pending).max(BigDecimal.ZERO);
        BigDecimal due = balance.signum() < 0 ? balance.negate() : BigDecimal.ZERO;
        return new WalletDto(balance, pending, available, due, weekEarnings, weekJobs, lifetime, currency);
    }

    @Transactional(readOnly = true)
    public PageResponse<LedgerLineDto> entries(UUID genieId, int page, int size) {
        long total = jdbc.sql(QueryUtil.get(QueryConstants.COUNT_WALLET_ENTRIES))
            .param("g", genieId).query(Long.class).single();
        List<LedgerLineDto> items = jdbc.sql(QueryUtil.get(QueryConstants.GET_WALLET_ENTRIES))
            .param("g", genieId)
            .param("limit", PageResponse.clampSize(size)).param("offset", PageResponse.offset(page, size))
            .query(LedgerLineDto.class).list();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    @Transactional(readOnly = true)
    public List<PayoutDto> payouts(UUID genieId) {
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_WALLET_PAYOUTS))
            .param("g", genieId).query(PayoutDto.class).list();
    }
}
