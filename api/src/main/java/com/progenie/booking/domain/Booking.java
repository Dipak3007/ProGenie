package com.progenie.booking.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import com.progenie.shared.error.ApiException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The booking aggregate. Every state change goes through a method here, which checks the current
 * state first, so illegal transitions (e.g. completing a cancelled job) are impossible.
 * Prices are a snapshot taken at booking time and are never re-quoted.
 *
 * <p>Not mapped: {@code customer_location} (PostGIS geography, written with SQL at creation);
 * {@code address_snapshot} is written once with SQL (insert "{}" here, then filled from the address).
 */
@Entity
@Table(name = "bookings")
public class Booking {

    public static final int MAX_START_CODE_ATTEMPTS = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_ref", nullable = false, updatable = false)
    private String bookingRef;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "genie_id", nullable = false, updatable = false)
    private UUID genieId;

    @Column(name = "service_id", nullable = false, updatable = false)
    private long serviceId;

    @Column(name = "city_id", nullable = false, updatable = false)
    private long cityId;

    @Column(name = "address_id", updatable = false)
    private UUID addressId;

    @Column(name = "address_snapshot", nullable = false, updatable = false)
    private String addressSnapshot = "{}";

    @Column(name = "slot_start", nullable = false)
    private Instant slotStart;

    @Column(name = "slot_end", nullable = false)
    private Instant slotEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BookingStatus status = BookingStatus.REQUESTED;

    private String notes;

    @Column(name = "service_amount", nullable = false, updatable = false)
    private BigDecimal serviceAmount;

    @Column(name = "extra_amount", nullable = false)
    private BigDecimal extraAmount = BigDecimal.ZERO.setScale(2);

    @Column(name = "extra_note")
    private String extraNote;

    @Column(name = "distance_km", nullable = false, updatable = false)
    private BigDecimal distanceKm;

    @Column(name = "travel_fee", nullable = false, updatable = false)
    private BigDecimal travelFee;

    @Column(name = "tip_amount", nullable = false)
    private BigDecimal tipAmount;

    @Column(name = "commission_rate", nullable = false, updatable = false)
    private BigDecimal commissionRate;

    @Column(name = "commission_amount", nullable = false, updatable = false)
    private BigDecimal commissionAmount;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(name = "genie_payout", nullable = false)
    private BigDecimal geniePayout;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", nullable = false)
    private PaymentMethod paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

    @Column(name = "rework_of_id", updatable = false)
    private UUID reworkOfId;               // a free redo of this booking (7-day warranty)

    @Column(name = "start_otp_hash")
    private String startOtpHash;

    @Column(name = "start_otp_attempts", nullable = false)
    private int startOtpAttempts;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancellation_fee", nullable = false)
    private BigDecimal cancellationFee = BigDecimal.ZERO.setScale(2);

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_fee_status")
    private FeeStatus cancellationFeeStatus;

    @Column(name = "idempotency_key", updatable = false)
    private String idempotencyKey;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "rescheduled_count", nullable = false)
    private int rescheduledCount;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private int version;

    protected Booking() {
        // for JPA
    }

    /** Price snapshot for a new booking (all INR, already rounded to paise). */
    public record Price(BigDecimal serviceAmount, BigDecimal distanceKm, BigDecimal travelFee, BigDecimal tipAmount,
                        BigDecimal commissionRate, BigDecimal commissionAmount) {
    }

    public static Booking request(String bookingRef, UUID customerId, UUID genieId, long serviceId, long cityId,
                                  UUID addressId, Instant slotStart, Instant slotEnd, Instant expiresAt, Price price,
                                  PaymentMethod paymentMethod, String notes, String idempotencyKey) {
        Booking b = new Booking();
        b.bookingRef = bookingRef;
        b.customerId = customerId;
        b.genieId = genieId;
        b.serviceId = serviceId;
        b.cityId = cityId;
        b.addressId = addressId;
        b.slotStart = slotStart;
        b.slotEnd = slotEnd;
        b.expiresAt = expiresAt;
        b.serviceAmount = price.serviceAmount();
        b.distanceKm = price.distanceKm();
        b.travelFee = price.travelFee();
        b.tipAmount = price.tipAmount();
        b.commissionRate = price.commissionRate();
        b.commissionAmount = price.commissionAmount();
        b.paymentMethod = paymentMethod;
        b.notes = notes;
        b.idempotencyKey = idempotencyKey;
        b.recalculate();
        return b;
    }

    /**
     * A free redo of a completed booking, decided by an admin on a complaint: nothing to pay, no commission.
     * The Genie still accepts the time like any request.
     */
    public static Booking rework(Booking original, String bookingRef, UUID genieId, Instant slotStart, Instant slotEnd,
                                 Instant expiresAt, String notes) {
        Price free = new Price(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            BigDecimal.ZERO);
        Booking b = request(bookingRef, original.customerId, genieId, original.serviceId, original.cityId,
            original.addressId, slotStart, slotEnd, expiresAt, free, PaymentMethod.CASH, notes, null);
        b.reworkOfId = original.id;
        b.paymentStatus = PaymentStatus.WAIVED;
        return b;
    }

    // ------------------------------------------------------------------ transitions

    public void accept(Instant now) {
        require(BookingStatus.REQUESTED, "accept");
        if (expiresAt != null && !now.isBefore(expiresAt)) {
            throw ApiException.unprocessable("REQUEST_EXPIRED", "This request has expired");
        }
        status = BookingStatus.ACCEPTED;
        acceptedAt = now;
    }

    public void decline(String reason) {
        require(BookingStatus.REQUESTED, "decline");
        status = BookingStatus.REJECTED;
        cancelReason = reason;
    }

    public void expire() {
        require(BookingStatus.REQUESTED, "expire");
        status = BookingStatus.EXPIRED;
    }

    /** Cancels an active, not yet started booking. A positive fee becomes due from the customer. */
    public void cancel(Instant now, UUID actorId, String reason, BigDecimal fee) {
        if (status != BookingStatus.REQUESTED && status != BookingStatus.ACCEPTED) {
            throw invalid("cancel");
        }
        status = BookingStatus.CANCELLED;
        cancelledAt = now;
        cancelledBy = actorId;
        cancelReason = reason;
        if (fee != null && fee.signum() > 0) {
            cancellationFee = fee.setScale(2, RoundingMode.HALF_UP);
            cancellationFeeStatus = FeeStatus.DUE;
        }
    }

    /** Moves the booking to a new slot; the Genie has to accept again. */
    public void reschedule(Instant newStart, Instant newEnd, Instant newExpiresAt) {
        if (status != BookingStatus.REQUESTED && status != BookingStatus.ACCEPTED) {
            throw invalid("reschedule");
        }
        slotStart = newStart;
        slotEnd = newEnd;
        expiresAt = newExpiresAt;
        status = BookingStatus.REQUESTED;
        acceptedAt = null;
        startOtpHash = null;
        startOtpAttempts = 0;
        rescheduledCount++;
    }

    /** Stores the hash of a fresh start code (the customer reads the code to the Genie at the door). */
    public void issueStartCode(String codeHash) {
        require(BookingStatus.ACCEPTED, "create a start code for");
        startOtpHash = codeHash;
        startOtpAttempts = 0;
    }

    /** Starts the job if the start code matches; wrong codes are counted and lock after 5 tries. */
    public void start(Instant now, boolean codeMatches) {
        require(BookingStatus.ACCEPTED, "start");
        if (startOtpHash == null) {
            throw ApiException.unprocessable("START_CODE_MISSING", "Ask the customer to open the booking and share the start code");
        }
        if (startOtpAttempts >= MAX_START_CODE_ATTEMPTS) {
            throw ApiException.unprocessable("START_CODE_LOCKED", "Too many wrong codes. Ask the customer to generate a new one");
        }
        if (!codeMatches) {
            startOtpAttempts++;
            throw ApiException.badRequest("WRONG_START_CODE", "The start code is not correct");
        }
        status = BookingStatus.IN_PROGRESS;
        startedAt = now;
        startOtpHash = null;
    }

    /** Finishes the job. Extra charges (parts/materials) go fully to the Genie; commission stays on the service price. */
    public void complete(Instant now, BigDecimal extra, String note, boolean cashCollected) {
        require(BookingStatus.IN_PROGRESS, "complete");
        extraAmount = (extra == null ? BigDecimal.ZERO : extra).setScale(2, RoundingMode.HALF_UP);
        extraNote = note;
        status = BookingStatus.COMPLETED;
        completedAt = now;
        if (paymentMethod == PaymentMethod.CASH && !cashCollected) {
            paymentMethod = PaymentMethod.ONLINE; // the customer will pay in the app instead
        }
        recalculate();
    }

    public void changeTip(BigDecimal tip) {
        if (paymentStatus != PaymentStatus.UNPAID || !(status.isActive() || status == BookingStatus.COMPLETED)) {
            throw ApiException.unprocessable("TIP_LOCKED", "The tip can no longer be changed for this booking");
        }
        tipAmount = tip.setScale(2, RoundingMode.HALF_UP);
        recalculate();
    }

    /** After a refund of (part of) the booking payment. */
    public void markRefunded(boolean full) {
        if (paymentStatus != PaymentStatus.PAID && paymentStatus != PaymentStatus.PARTIALLY_REFUNDED) {
            throw invalid("refund");
        }
        paymentStatus = full ? PaymentStatus.REFUNDED : PaymentStatus.PARTIALLY_REFUNDED;
    }

    public void markPaid() {
        if (status != BookingStatus.COMPLETED) {
            throw invalid("mark as paid");
        }
        paymentStatus = PaymentStatus.PAID;
    }

    public void markCancellationFeePaid() {
        if (cancellationFeeStatus != FeeStatus.DUE) {
            throw ApiException.unprocessable("NO_FEE_DUE", "No cancellation fee is due");
        }
        cancellationFeeStatus = FeeStatus.PAID;
    }

    public void waiveCancellationFee() {
        if (cancellationFeeStatus != FeeStatus.DUE) {
            throw ApiException.unprocessable("NO_FEE_DUE", "No cancellation fee is due");
        }
        cancellationFeeStatus = FeeStatus.WAIVED;
    }

    private void recalculate() {
        totalAmount = serviceAmount.add(extraAmount).add(travelFee).add(tipAmount).setScale(2, RoundingMode.HALF_UP);
        geniePayout = totalAmount.subtract(commissionAmount);
    }

    private void require(BookingStatus expected, String action) {
        if (status != expected) {
            throw invalid(action);
        }
    }

    private ApiException invalid(String action) {
        return ApiException.unprocessable("INVALID_STATE", "Cannot " + action + " a booking that is " + status);
    }

    // ------------------------------------------------------------------ getters

    public UUID getReworkOfId() {
        return reworkOfId;
    }


    public UUID getId() {
        return id;
    }

    public String getBookingRef() {
        return bookingRef;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getGenieId() {
        return genieId;
    }

    public long getServiceId() {
        return serviceId;
    }

    public UUID getAddressId() {
        return addressId;
    }

    public Instant getSlotStart() {
        return slotStart;
    }

    public Instant getSlotEnd() {
        return slotEnd;
    }

    public BookingStatus getStatus() {
        return status;
    }

    public BigDecimal getServiceAmount() {
        return serviceAmount;
    }

    public BigDecimal getExtraAmount() {
        return extraAmount;
    }

    public BigDecimal getTravelFee() {
        return travelFee;
    }

    public BigDecimal getTipAmount() {
        return tipAmount;
    }

    public BigDecimal getCommissionAmount() {
        return commissionAmount;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public BigDecimal getGeniePayout() {
        return geniePayout;
    }

    public PaymentMethod getPaymentMethod() {
        return paymentMethod;
    }

    public PaymentStatus getPaymentStatus() {
        return paymentStatus;
    }

    public BigDecimal getCancellationFee() {
        return cancellationFee;
    }

    public FeeStatus getCancellationFeeStatus() {
        return cancellationFeeStatus;
    }

    public String getStartOtpHash() {
        return startOtpHash;
    }

    public int getRescheduledCount() {
        return rescheduledCount;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
