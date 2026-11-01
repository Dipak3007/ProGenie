package com.progenie.booking;

import java.util.UUID;

/**
 * Booked for someone else (Phase 3.5A): the Genie has set off, so the person at the address gets their own start code
 * and a tracking link ({@code /track/{trackToken}}). Published inside the transaction; the notification module sends
 * it by SMS and WhatsApp. {@code startCode} is plain text only here and in the (redacted after sending) message.
 */
public record ContactOnTheWay(UUID bookingId, String contactName, String contactPhone, String startCode,
                              String trackToken) {
}
