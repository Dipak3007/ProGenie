package com.progenie.booking.domain;

/** State of a late-cancellation fee. A customer with a DUE fee cannot book again until it is paid. */
public enum FeeStatus {
    DUE, PAID, WAIVED
}
