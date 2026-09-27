package com.progenie.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.progenie.payment.LedgerPostings.Entry;
import org.junit.jupiter.api.Test;

class RefundPostingsTest {

    private static final UUID BOOKING = UUID.randomUUID();
    private static final UUID GENIE = UUID.randomUUID();

    private static BigDecimal amount(List<Entry> entries, String account) {
        return entries.stream().filter(e -> e.account().equals(account)).map(Entry::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void fullGenieRefundUndoesTheOriginalPosting() {
        // payment of 479 with 20 commission (service 400 at 5%, travel 59, tip 20)
        List<Entry> e = LedgerPostings.refund(BOOKING, GENIE, "PG-1", new BigDecimal("479.00"), BigDecimal.ZERO,
            new BigDecimal("479.00"), new BigDecimal("20.00"));
        assertThat(LedgerPostings.debits(e)).isEqualByComparingTo(LedgerPostings.credits(e));
        assertThat(amount(e, "CUSTOMER")).isEqualByComparingTo("479.00");
        assertThat(amount(e, "PLATFORM_COMMISSION")).isEqualByComparingTo("20.00");
        assertThat(amount(e, "GENIE_EARNINGS")).isEqualByComparingTo("459.00");
        assertThat(e).noneMatch(x -> x.account().equals("PLATFORM_GOODWILL"));
    }

    @Test
    void splitRefundCostsBothSidesAndStaysBalanced() {
        List<Entry> e = LedgerPostings.refund(BOOKING, GENIE, "PG-1", new BigDecimal("10.00"), new BigDecimal("15.00"),
            new BigDecimal("259.00"), new BigDecimal("10.00"));
        assertThat(LedgerPostings.debits(e)).isEqualByComparingTo("25.00");
        assertThat(LedgerPostings.credits(e)).isEqualByComparingTo("25.00");
        assertThat(amount(e, "PLATFORM_GOODWILL")).isEqualByComparingTo("15.00");
        assertThat(amount(e, "PLATFORM_COMMISSION")).isEqualByComparingTo("0.39");
        assertThat(amount(e, "GENIE_EARNINGS")).isEqualByComparingTo("9.61");
        assertThat(e.stream().filter(x -> x.account().equals("GENIE_EARNINGS")).findFirst().orElseThrow().genieId())
            .isEqualTo(GENIE);
    }

    @Test
    void goodwillRefundNeverTouchesTheGenie() {
        List<Entry> e = LedgerPostings.refund(BOOKING, GENIE, "PG-1", BigDecimal.ZERO, new BigDecimal("49.00"),
            new BigDecimal("49.00"), BigDecimal.ZERO);
        assertThat(e).extracting(Entry::account).containsExactlyInAnyOrder("CUSTOMER", "PLATFORM_GOODWILL");
    }
}
