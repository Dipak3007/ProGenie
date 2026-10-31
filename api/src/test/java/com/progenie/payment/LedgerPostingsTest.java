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
            new BigDecimal("24.95"), new BigDecimal("773.00"), new BigDecimal("748.05"), new BigDecimal("49.00"), "DUE", BigDecimal.ZERO, BigDecimal.ZERO);
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

    /** Coupon WELCOME50 on the same job: the customer pays 723, ProGenie pays 50, the Genie's share is unchanged. */
    @Test
    void aCouponIsPaidByProGenieAndTheGenieEarnsTheSame() {
        Payable withCoupon = new Payable(UUID.randomUUID(), "PG-2026-000003", UUID.randomUUID(), GENIE, "COMPLETED", "ONLINE",
            "UNPAID", new BigDecimal("499.00"), new BigDecimal("150.00"), new BigDecimal("74.00"), new BigDecimal("50.00"),
            new BigDecimal("24.95"), new BigDecimal("723.00"), new BigDecimal("748.05"), BigDecimal.ZERO, null,
            new BigDecimal("50.00"), BigDecimal.ZERO);
        List<Entry> online = LedgerPostings.bookingPaid(withCoupon, false);
        assertThat(LedgerPostings.debits(online)).isEqualByComparingTo("773.00").isEqualByComparingTo(LedgerPostings.credits(online));
        assertThat(online).filteredOn(e -> e.account().equals("CUSTOMER")).singleElement()
            .extracting(Entry::amount).isEqualTo(new BigDecimal("723.00"));
        assertThat(online).filteredOn(e -> e.account().equals("PLATFORM_PROMOTIONS")).singleElement()
            .satisfies(e -> assertThat(e.direction()).isEqualTo("D"))
            .extracting(Entry::amount).isEqualTo(new BigDecimal("50.00"));
        assertThat(walletChange(online)).isEqualByComparingTo("748.05");
        // cash: the Genie collected 723 instead of 773, so ProGenie owes them the 50 (minus the commission as usual)
        List<Entry> cash = LedgerPostings.bookingPaid(withCoupon, true);
        assertThat(LedgerPostings.debits(cash)).isEqualByComparingTo(LedgerPostings.credits(cash));
        assertThat(walletChange(cash)).isEqualByComparingTo("25.05");
    }

    /** Genie Now (3C-9): the ₹40 instant fee is paid by the customer and earned in full by the Genie. */
    @Test
    void theInstantFeeGoesToTheGenieLikeTheTravelFee() {
        Payable instant = new Payable(UUID.randomUUID(), "PG-2026-000004", UUID.randomUUID(), GENIE, "COMPLETED", "ONLINE",
            "UNPAID", new BigDecimal("499.00"), BigDecimal.ZERO, new BigDecimal("74.00"), BigDecimal.ZERO,
            new BigDecimal("24.95"), new BigDecimal("613.00"), new BigDecimal("588.05"), BigDecimal.ZERO, null,
            BigDecimal.ZERO, new BigDecimal("40.00"));
        List<Entry> online = LedgerPostings.bookingPaid(instant, false);
        assertThat(LedgerPostings.debits(online)).isEqualByComparingTo("613.00").isEqualByComparingTo(LedgerPostings.credits(online));
        assertThat(walletChange(online)).isEqualByComparingTo("588.05");
        assertThat(walletChange(LedgerPostings.bookingPaid(instant, true))).isEqualByComparingTo("-24.95");
    }

    @Test
    void zeroAmountsAreNotPosted() {
        Payable noTip = new Payable(UUID.randomUUID(), "PG-2026-000002", UUID.randomUUID(), GENIE, "COMPLETED", "CASH", "UNPAID",
            new BigDecimal("299.00"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            new BigDecimal("14.95"), new BigDecimal("299.00"), new BigDecimal("284.05"), BigDecimal.ZERO, null, BigDecimal.ZERO, BigDecimal.ZERO);
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
