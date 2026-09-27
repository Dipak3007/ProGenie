package com.progenie.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.progenie.booking.BookingPayments.Payable;
import com.progenie.payment.LedgerPostings.Entry;
import org.junit.jupiter.api.Test;

class LedgerPostingsTest {

    private static final UUID GENIE = UUID.randomUUID();

    /** 499 service + 150 extra + 74 travel + 50 tip = 773 total; commission 5% of 499 = 24.95. */
    private static Payable job() {
        return new Payable(UUID.randomUUID(), "PG-2026-000001", UUID.randomUUID(), GENIE, "COMPLETED", "ONLINE", "UNPAID",
            new BigDecimal("499.00"), new BigDecimal("150.00"), new BigDecimal("74.00"), new BigDecimal("50.00"),
            new BigDecimal("24.95"), new BigDecimal("773.00"), new BigDecimal("748.05"), new BigDecimal("49.00"), "DUE");
    }

    private static BigDecimal walletChange(List<Entry> entries) {
        return entries.stream()
            .filter(e -> GENIE.equals(e.genieId()))
            .map(e -> "C".equals(e.direction()) ? e.amount() : e.amount().negate())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void onlinePaymentIsBalancedAndCreditsTheGenieTotalMinusCommission() {
        List<Entry> entries = LedgerPostings.bookingPaid(job(), false);
        assertThat(LedgerPostings.debits(entries)).isEqualByComparingTo("773.00");
        assertThat(LedgerPostings.credits(entries)).isEqualByComparingTo("773.00");
        assertThat(walletChange(entries)).isEqualByComparingTo("748.05");
        assertThat(entries).extracting(Entry::account)
            .containsExactly("CUSTOMER", "PLATFORM_COMMISSION", "GENIE_EARNINGS", "TIPS");
    }

    @Test
    void cashPaymentLeavesTheGenieOwingOnlyTheCommission() {
        List<Entry> entries = LedgerPostings.bookingPaid(job(), true);
        assertThat(LedgerPostings.debits(entries)).isEqualByComparingTo(LedgerPostings.credits(entries));
        assertThat(entries.getFirst().account()).isEqualTo("GENIE_CASH");
        assertThat(walletChange(entries)).isEqualByComparingTo("-24.95");
    }

    @Test
    void zeroAmountsAreNotPosted() {
        Payable noTip = new Payable(UUID.randomUUID(), "PG-2026-000002", UUID.randomUUID(), GENIE, "COMPLETED", "CASH", "UNPAID",
            new BigDecimal("299.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("14.95"), new BigDecimal("299.00"), new BigDecimal("284.05"), BigDecimal.ZERO, null);
        assertThat(LedgerPostings.bookingPaid(noTip, true)).extracting(Entry::account)
            .containsExactly("GENIE_CASH", "PLATFORM_COMMISSION", "GENIE_EARNINGS");
    }

    @Test
    void lateCancellationFeeGoesToTheGenie() {
        List<Entry> entries = LedgerPostings.cancellationFeePaid(job());
        assertThat(LedgerPostings.debits(entries)).isEqualByComparingTo(LedgerPostings.credits(entries));
        assertThat(walletChange(entries)).isEqualByComparingTo("49.00");
    }
}
