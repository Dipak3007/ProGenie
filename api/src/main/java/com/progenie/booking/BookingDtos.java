package com.progenie.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.progenie.booking.domain.PaymentMethod;
import com.progenie.shared.util.ServiceLines;
import com.progenie.shared.util.ServiceLines.ServiceLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import com.progenie.shared.util.CustomerRatingRules;
import org.jspecify.annotations.Nullable;

/** Requests and read models for bookings (customer, Genie and admin views share the same shapes). */
public final class BookingDtos {

    private BookingDtos() {
    }

    // ------------------------------------------------------------------ requests

    /**
     * A new booking: one service ({@code serviceId}) or several from the same Genie ({@code items}, Phase 3.5C: up to
     * 5 services, each 1–5 times, 8 hours at most together).
     */
    public record CreateBookingRequest(
        @NotNull UUID genieId,
        @Nullable Long serviceId,
        @Nullable @Size(max = ServiceLines.MAX_SERVICES) List<@Valid ServiceLine> items,
        @NotNull UUID addressId,
        @NotNull OffsetDateTime slotStart,
        @NotNull PaymentMethod paymentMethod,
        @DecimalMin("0") @DecimalMax("2000") BigDecimal tipAmount,
        @Size(max = 500) String notes,
        @Size(max = 30) String couponCode,
        @Nullable @DecimalMin("0") BigDecimal expectedTotal) {
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
                                    @Nullable String cancellationFeeStatus, boolean reviewed,
                                    @Nullable BigDecimal customerRating, @Nullable Integer customerRatingCount) {

        /** Customers never see their own rating; Genies only once it rests on enough ratings. */
        BookingSummaryDto forViewer(boolean showCustomerRating) {
            boolean show = showCustomerRating && customerRatingCount != null
                && customerRatingCount >= CustomerRatingRules.MIN_SHOWN;
            return show ? this : new BookingSummaryDto(id, bookingRef, status, serviceName, categorySlug, counterpartName,
                slotStart, slotEnd, totalAmount, paymentMethod, paymentStatus, area, cancellationFeeStatus, reviewed, null, null);
        }
    }

    /**
     * @param ratingCount  for the customer: how many Genies rated them (shown to Genies from 3 ratings, to admins always)
     * @param flagReason   admins only: why this customer needs attention (Genies' ratings), else null
     */
    public record PartyDto(UUID id, String name, @Nullable String phone, @Nullable BigDecimal avgRating,
                           @Nullable Integer ratingCount, @Nullable String flagReason) {
    }

    /** {@code contactName}/{@code contactPhone}: the person at the address when the customer booked for someone else. */
    public record AddressView(@Nullable String label, @Nullable String line1, @Nullable String line2, @Nullable String landmark, @Nullable String area, @Nullable String city,
                              @Nullable String pincode, @Nullable String contactName, @Nullable String contactPhone) {
    }

    /** The Genie's rating of the customer for this booking (Genie and admin views only). */
    public record CustomerRatingView(int rating, List<String> tags, OffsetDateTime createdAt) {
    }

    /**
     * A service on the booking (Phase 3.5C). {@code amount} = unit price × quantity; {@code durationMinutes} is per
     * unit; {@code discountAmount} is this line's share of the coupon discount.
     */
    public record BookingItemDto(long id, @Nullable Long serviceId, String serviceName, int quantity, BigDecimal unitPrice,
                                 BigDecimal amount, int durationMinutes, BigDecimal discountAmount) {
    }

    public record HistoryDto(@Nullable String fromStatus, String toStatus, @Nullable String actorRole, @Nullable String reason, OffsetDateTime changedAt) {
    }

    /**
     * One booking as seen by {@code viewer}. Contact details are only shared while the job is active
     * and accepted; the Genie sees the full address only after accepting.
     *
     * @param allowedActions what this viewer can do next, e.g. CANCEL, RESCHEDULE, PAY, TRACK, ACCEPT, START_TRAVEL, START,
     *                       REMOVE_SERVICE (Phase 3.5C: the customer can drop one of several services until the Genie sets off),
     *                       SEND_QUOTE (Genie, after an inspection), ACCEPT_QUOTE / DECLINE_QUOTE (customer, Phase 3.5D)
     * @param kind           JOB, or INSPECTION (a visit to see a big job; {@code quote} is the Genie's quote after it)
     * @param quote          for an inspection: the quote sent after it; for a job made from a quote: that quote
     * @param inspectionCredit a job made from a quote: the inspection fee already paid, taken off {@code serviceAmount}
     * @param serviceName    all services on the booking ("Fan installation ×2 + Switchboard repair"); {@code serviceId}
     *                       is the first one, {@code items} has them all
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
                                   List<String> allowedActions, List<HistoryDto> history, List<BookingPhotoDto> photos,
                                   BigDecimal discountAmount, @Nullable String couponCode,
                                   @Nullable OffsetDateTime travelStartedAt, BigDecimal instantFee, boolean instant,
                                   @Nullable UUID addressId, @Nullable CustomerRatingView customerRating,
                                   List<BookingItemDto> items, String kind, BigDecimal inspectionCredit,
                                   @Nullable QuoteDto quote) {
    }

    // ------------------------------------------------------------------ quotes (Phase 3.5D)

    /**
     * A line of a quote: one of the Genie's services ({@code serviceId}) or a custom line (no service, e.g. "Replace
     * 20 m of wiring"). The description defaults to the service's name.
     */
    public record QuoteLineRequest(@Nullable Long serviceId, @Nullable @Size(max = 150) String description,
                                   @Min(1) @Max(10) int quantity,
                                   @NotNull @DecimalMin("1") @DecimalMax("200000") BigDecimal unitPrice) {
    }

    /** What the Genie sends after an inspection: 1–10 lines, how long the job takes, an optional note. */
    public record QuoteRequest(@NotEmpty @Size(max = 10) List<@Valid QuoteLineRequest> lines,
                               @Min(30) @Max(480) int durationMinutes, @Nullable @Size(max = 500) String note) {
    }

    /** The customer accepts a quote by picking a time: the job is booked (already accepted by the Genie). */
    public record AcceptQuoteRequest(@NotNull OffsetDateTime slotStart, @NotNull PaymentMethod paymentMethod,
                                     @DecimalMin("0") @DecimalMax("2000") BigDecimal tipAmount,
                                     @Nullable @Size(max = 500) String notes) {
    }

    public record QuoteLineDto(long id, @Nullable Long serviceId, String description, int quantity, BigDecimal unitPrice,
                               BigDecimal amount) {
    }

    /**
     * A Genie's quote after an inspection visit.
     *
     * @param status           SENT (waiting for the customer), ACCEPTED, DECLINED or EXPIRED (7 days after the latest version)
     * @param inspectionCredit the inspection fee already paid, taken off the job
     * @param jobAmount        what the services cost after that credit (the travel fee is added when booking)
     */
    public record QuoteDto(UUID id, UUID inspectionBookingId, String inspectionBookingRef, String status, int version,
                           List<QuoteLineDto> lines, BigDecimal totalAmount, BigDecimal inspectionCredit, BigDecimal jobAmount,
                           int durationMinutes, @Nullable String note, OffsetDateTime validUntil,
                           @Nullable String declineReason, @Nullable OffsetDateTime respondedAt, @Nullable UUID jobBookingId,
                           @Nullable String jobBookingRef, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    /**
     * A before/after job photo. {@code url} opens the image directly and stops working at {@code expiresAt}
     * (a few minutes); fetch the booking again for fresh links.
     *
     * @param stage BEFORE (taken when starting the job) or AFTER (when finishing it)
     */
    public record BookingPhotoDto(UUID id, String stage, String url, Instant expiresAt, String contentType,
                                  int sizeBytes, OffsetDateTime createdAt) {
    }
}
