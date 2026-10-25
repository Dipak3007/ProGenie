package com.progenie.payment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** Payment, wallet and payout requests and read models. */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    /** What the app needs to open the gateway checkout. */
    /** {@code keyId}: the gateway's public key for the web checkout (Razorpay); null with the fake gateway. */
    public record PaymentOrderDto(UUID paymentId, String provider, String orderId, BigDecimal amount, String currency,
                                  String purpose, String bookingRef, @Nullable String keyId) {
    }

    public record ConfirmPaymentRequest(@NotBlank @Size(max = 100) String providerPaymentId,
                                        @NotBlank @Size(max = 200) String signature) {
    }

    public record PaymentDto(UUID id, String purpose, String method, @Nullable String provider, BigDecimal amount, String status,
                             @Nullable String failureReason, OffsetDateTime createdAt, BigDecimal refundedAmount) {
    }

    /** Published when money for a booking is received (cash or online). */
    public record PaymentReceived(UUID paymentId, UUID bookingId, String bookingRef, UUID customerId, UUID genieId, BigDecimal amount,
                                  String purpose, String method) {
    }

    public record WalletDto(BigDecimal balance, BigDecimal pendingPayouts, BigDecimal availableForPayout,
                            BigDecimal commissionDue, BigDecimal earningsThisWeek, int jobsThisWeek,
                            BigDecimal lifetimeEarnings, String currency) {
    }

    public record LedgerLineDto(long id, @Nullable String bookingRef, String account, String direction, BigDecimal amount,
                                @Nullable String description, OffsetDateTime createdAt) {
    }

    public record PayoutDto(UUID id, UUID genieId, String genieName, @Nullable String upiId, LocalDate periodStart,
                            LocalDate periodEnd, BigDecimal amount, String status, @Nullable String reference,
                            OffsetDateTime createdAt, @Nullable OffsetDateTime paidAt) {
    }

    public record GeneratePayoutsRequest(LocalDate periodEnd) {
    }

    public record MarkPaidRequest(@NotBlank @Size(max = 100) String reference) {
    }

    public record MarkFailedRequest(@NotBlank @Size(max = 100) String reason) {
    }

    public record SettlementRequest(@NotNull @DecimalMin("1") @DecimalMax("100000") BigDecimal amount,
                                    @NotBlank @Size(max = 100) String reference) {
    }

    /** An admin marked a weekly payout as sent. */
    public record PayoutPaid(UUID payoutId, UUID genieId, BigDecimal amount, String reference, LocalDate periodStart,
                             LocalDate periodEnd) {
    }

    /** A refund has actually been paid out (gateway confirmed, or recorded as a manual transfer). */
    public record RefundProcessed(UUID refundId, UUID paymentId, UUID bookingId, String bookingRef, UUID customerId,
                                  UUID genieId, BigDecimal amount, BigDecimal genieShare, String method) {
    }
}
