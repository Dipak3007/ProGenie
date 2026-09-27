package com.progenie.booking.domain;

/** Payment state of the booking amount (not of the cancellation fee). */
public enum PaymentStatus {
    UNPAID, PAID, PARTIALLY_REFUNDED, REFUNDED, WAIVED
}
