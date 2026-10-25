package com.progenie.booking;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.progenie.booking.domain.PaymentMethod;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** Requests and read models for bookings (customer, Genie and admin views share the same shapes). */
public final class BookingDtos {

    private BookingDtos() {
    }

    // ------------------------------------------------------------------ requests

    public record CreateBookingRequest(
        @NotNull UUID genieId,
        @NotNull Long serviceId,
        @NotNull UUID addressId,
        @NotNull OffsetDateTime slotStart,
        @NotNull PaymentMethod paymentMethod,
        @DecimalMin("0") @DecimalMax("2000") BigDecimal tipAmount,
        @Size(max = 500) String notes) {
    }

    public record CancelRequest(@Size(max = 300) String reason) {
    }

    public record DeclineRequest(@NotBlank @Size(max = 300) String reason) {
    }

    public record RescheduleRequest(@NotNull OffsetDateTime slotStart) {
    }

    public record TipRequest(@NotNull @DecimalMin("0") @DecimalMax("2000") BigDecimal tipAmount) {
    }

    public record StartRequest(@NotBlank @Pattern(regexp = "^[0-9]{4}$", message = "must be 4 digits") String code) {
    }

    /** @param cashCollected for CASH bookings: true if the Genie took the cash; false switches the booking to online payment */
    public record CompleteRequest(@DecimalMin("0") @DecimalMax("20000") BigDecimal extraAmount,
                                  @Size(max = 300) String extraNote,
                                  boolean cashCollected) {
    }

    public record StartCodeDto(String code, String message) {
    }

    // ------------------------------------------------------------------ read models

    public record BookingSummaryDto(UUID id, String bookingRef, String status, String serviceName, String categorySlug,
                                    String counterpartName, OffsetDateTime slotStart, OffsetDateTime slotEnd,
                                    BigDecimal totalAmount, String paymentMethod, String paymentStatus, @Nullable String area,
                                    @Nullable String cancellationFeeStatus, boolean reviewed) {
    }

    public record PartyDto(UUID id, String name, @Nullable String phone, @Nullable BigDecimal avgRating) {
    }

    public record AddressView(@Nullable String label, @Nullable String line1, @Nullable String line2, @Nullable String landmark, @Nullable String area, @Nullable String city,
                              @Nullable String pincode) {
    }

    public record HistoryDto(@Nullable String fromStatus, String toStatus, @Nullable String actorRole, @Nullable String reason, OffsetDateTime changedAt) {
    }

    /**
     * One booking as seen by {@code viewer}. Contact details are only shared while the job is active
     * and accepted; the Genie sees the full address only after accepting.
     *
     * @param allowedActions what this viewer can do next, e.g. CANCEL, RESCHEDULE, PAY, ACCEPT, START
     */
    public record BookingDetailDto(UUID id, String bookingRef, String status, long serviceId, String serviceName,
                                   String categoryName, String categorySlug, PartyDto genie, PartyDto customer,
                                   @Nullable AddressView address, OffsetDateTime slotStart, OffsetDateTime slotEnd,
                                   @Nullable OffsetDateTime expiresAt, @Nullable String notes, BigDecimal serviceAmount,
                                   BigDecimal extraAmount, @Nullable String extraNote, BigDecimal distanceKm,
                                   BigDecimal travelFee, BigDecimal tipAmount, BigDecimal totalAmount,
                                   BigDecimal commissionAmount, BigDecimal geniePayout, String paymentMethod,
                                   String paymentStatus, @Nullable BigDecimal cancellationFee, @Nullable String cancellationFeeStatus,
                                   @Nullable BigDecimal cancellationFeeIfCancelledNow, @Nullable String cancelReason,
                                   @Nullable String cancelledByRole, int rescheduledCount, boolean reviewed,
                                   OffsetDateTime createdAt, @Nullable OffsetDateTime acceptedAt, @Nullable OffsetDateTime startedAt,
                                   @Nullable OffsetDateTime completedAt, @Nullable OffsetDateTime cancelledAt,
                                   List<String> allowedActions, List<HistoryDto> history) {
    }
}
