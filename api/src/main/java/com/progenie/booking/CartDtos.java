package com.progenie.booking;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.progenie.booking.domain.PaymentMethod;
import com.progenie.pricing.PriceCalculator.PriceBreakdown;
import com.progenie.shared.util.ServiceLines;
import com.progenie.shared.util.ServiceLines.ServiceLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** The customer's cart (Phase 3.5E): one saved checkout, re-checked whenever it is read. */
public final class CartDtos {

    private CartDtos() {
    }

    /**
     * Saves what the customer has picked so far. {@code seenTotal}: the total on their screen (a later price change is
     * then reported). {@code replace}: replace a cart that holds another Genie's services (else 409 CART_OTHER_GENIE).
     */
    public record CartRequest(
        @NotNull UUID genieId,
        @NotEmpty @Size(max = ServiceLines.MAX_SERVICES) List<@Valid ServiceLine> items,
        @Nullable UUID addressId,
        @Nullable OffsetDateTime slotStart,
        @Nullable PaymentMethod paymentMethod,
        @Nullable @DecimalMin("0") @DecimalMax("2000") BigDecimal tipAmount,
        @Nullable @Size(max = 500) String notes,
        @Nullable @Size(max = 30) String couponCode,
        @Nullable @DecimalMin("0") BigDecimal seenTotal,
        boolean replace) {
    }

    public record CartItemDto(long serviceId, String name, int quantity, BigDecimal unitPrice, BigDecimal amount,
                              int durationMinutes, boolean inspectionFirst) {
    }

    /**
     * The cart as it stands now. {@code estimate} and {@code total} (after the coupon) need an address;
     * {@code couponDiscount} is set when the coupon still applies.
     */
    public record CartDto(UUID genieId, String genieName, List<CartItemDto> items, String itemsParam,
                          @Nullable UUID addressId, @Nullable String addressLabel, @Nullable OffsetDateTime slotStart,
                          @Nullable String paymentMethod, BigDecimal tipAmount, @Nullable String notes,
                          @Nullable String couponCode, @Nullable BigDecimal couponDiscount,
                          @Nullable PriceBreakdown estimate, @Nullable BigDecimal total, int durationMinutes,
                          boolean inspection, OffsetDateTime updatedAt, OffsetDateTime expiresAt) {
    }

    /** {@code cart} null = empty. {@code notices}: what the re-check changed ("The price changed from ₹… to ₹…"). */
    public record CartResponse(@Nullable CartDto cart, List<String> notices) {
    }
}
