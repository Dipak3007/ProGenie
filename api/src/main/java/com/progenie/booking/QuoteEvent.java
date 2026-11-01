package com.progenie.booking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A quote after an inspection visit (Phase 3.5D) was sent (or revised), accepted or declined. Listeners tell the other
 * side. {@code bookingRef}: the inspection's, or for ACCEPTED the new job's (whose time is {@code jobStart}).
 */
public record QuoteEvent(Type type, UUID quoteId, UUID inspectionBookingId, String bookingRef, UUID customerId,
                         UUID genieId, int version, BigDecimal totalAmount, @Nullable Instant jobStart,
                         @Nullable UUID jobBookingId) {

    public enum Type { SENT, ACCEPTED, DECLINED }
}
