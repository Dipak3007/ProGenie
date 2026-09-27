package com.progenie.booking.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.progenie.shared.error.ApiException;
import org.junit.jupiter.api.Test;

class BookingTest {

    private static final Instant NOW = Instant.parse("2026-10-05T03:00:00Z");
    private static final Instant SLOT = NOW.plus(Duration.ofHours(5));

    private static Booking booking(PaymentMethod method) {
        Booking.Price price = new Booking.Price(new BigDecimal("499.00"), new BigDecimal("7.40"), new BigDecimal("84.00"),
            new BigDecimal("20.00"), new BigDecimal("0.0500"), new BigDecimal("24.95"));
        return Booking.request("PG-2026-000001", UUID.randomUUID(), UUID.randomUUID(), 1L, 1L, UUID.randomUUID(),
            SLOT, SLOT.plus(Duration.ofHours(1)), NOW.plus(Duration.ofMinutes(30)), price, method, null, null);
    }

    @Test
    void totalsAreComputedFromTheSnapshot() {
        Booking b = booking(PaymentMethod.CASH);
        assertThat(b.getTotalAmount()).isEqualByComparingTo("603.00");   // 499 + 84 travel + 20 tip
        assertThat(b.getGeniePayout()).isEqualByComparingTo("578.05");   // total − 24.95 commission
    }

    @Test
    void happyPathThroughEveryState() {
        Booking b = booking(PaymentMethod.CASH);
        b.accept(NOW.plus(Duration.ofMinutes(5)));
        b.issueStartCode("hash");
        b.start(SLOT, true);
        b.complete(SLOT.plus(Duration.ofHours(1)), new BigDecimal("150"), "Parts", true);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(b.getTotalAmount()).isEqualByComparingTo("753.00");
        assertThat(b.getGeniePayout()).isEqualByComparingTo("728.05"); // commission stays on the service price only
        assertThat(b.getPaymentMethod()).isEqualTo(PaymentMethod.CASH);
    }

    @Test
    void cannotAcceptAfterTheRequestExpired() {
        Booking b = booking(PaymentMethod.CASH);
        assertThatThrownBy(() -> b.accept(NOW.plus(Duration.ofMinutes(31))))
            .isInstanceOf(ApiException.class).hasMessageContaining("expired");
    }

    @Test
    void illegalTransitionsAreRejected() {
        Booking b = booking(PaymentMethod.CASH);
        assertThatThrownBy(() -> b.start(NOW, true)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> b.complete(NOW, null, null, true)).isInstanceOf(ApiException.class);
        b.decline("Busy");
        assertThatThrownBy(() -> b.cancel(NOW, UUID.randomUUID(), "x", BigDecimal.ZERO)).isInstanceOf(ApiException.class);
    }

    @Test
    void lateCancellationRecordsAFeeDue() {
        Booking b = booking(PaymentMethod.CASH);
        b.accept(NOW);
        b.cancel(SLOT.minus(Duration.ofHours(1)), b.getCustomerId(), "Plans changed", new BigDecimal("49"));
        assertThat(b.getCancellationFeeStatus()).isEqualTo(FeeStatus.DUE);
        assertThat(b.getCancellationFee()).isEqualByComparingTo("49.00");
        b.markCancellationFeePaid();
        assertThat(b.getCancellationFeeStatus()).isEqualTo(FeeStatus.PAID);
    }

    @Test
    void wrongStartCodesLockAfterFiveTries() {
        Booking b = booking(PaymentMethod.CASH);
        b.accept(NOW);
        b.issueStartCode("hash");
        for (int i = 0; i < Booking.MAX_START_CODE_ATTEMPTS; i++) {
            assertThatThrownBy(() -> b.start(SLOT, false)).hasMessageContaining("not correct");
        }
        assertThatThrownBy(() -> b.start(SLOT, true)).hasMessageContaining("Too many");
        b.issueStartCode("new-hash");  // the customer generates a new code
        b.start(SLOT, true);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.IN_PROGRESS);
    }

    @Test
    void cashNotCollectedSwitchesToOnlinePayment() {
        Booking b = booking(PaymentMethod.CASH);
        b.accept(NOW);
        b.issueStartCode("hash");
        b.start(SLOT, true);
        b.complete(SLOT.plus(Duration.ofHours(1)), BigDecimal.ZERO, null, false);
        assertThat(b.getPaymentMethod()).isEqualTo(PaymentMethod.ONLINE);
        assertThat(b.getPaymentStatus()).isEqualTo(PaymentStatus.UNPAID);
    }

    @Test
    void tipCannotChangeOncePaid() {
        Booking b = booking(PaymentMethod.ONLINE);
        b.changeTip(new BigDecimal("100"));
        assertThat(b.getTotalAmount()).isEqualByComparingTo("683.00");
        b.accept(NOW);
        b.issueStartCode("hash");
        b.start(SLOT, true);
        b.complete(SLOT.plus(Duration.ofHours(1)), null, null, false);
        b.markPaid();
        assertThatThrownBy(() -> b.changeTip(BigDecimal.TEN)).hasMessageContaining("no longer");
    }
}
