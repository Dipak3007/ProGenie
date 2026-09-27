package com.progenie.booking;

import java.time.Instant;
import java.util.UUID;

/** Published once per accepted booking, about an hour before its slot (the notification module sends the reminders). */
public record BookingReminderDue(UUID bookingId, String bookingRef, UUID customerId, UUID genieId, Instant slotStart) {
}
