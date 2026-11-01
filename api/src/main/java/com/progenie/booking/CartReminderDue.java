package com.progenie.booking;

import java.util.UUID;

/** A cart was left alone for a day (Phase 3.5E): one in-app reminder. {@code services}: "Fan installation ×2 + …". */
public record CartReminderDue(UUID customerId, String genieName, String services) {
}
