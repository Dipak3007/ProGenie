/**
 * Booking module: slots, the booking lifecycle and its rules.
 *
 * <p>REQUESTED → ACCEPTED → IN_PROGRESS → COMPLETED, with REJECTED (Genie declined), EXPIRED (no answer
 * in time) and CANCELLED (customer, Genie or admin). The Genie starts a job with a 4-digit code the
 * customer shares at the door. Customers cancel free until 2 hours before the slot; later, a fee goes
 * to the Genie. Every change is written to booking_status_history and published as a {@link com.progenie.booking.BookingEvent}.
 */
package com.progenie.booking;
