package com.progenie.support;

import java.time.Duration;

/** Complaint categories with their priority and deadlines (design doc 16.4). */
public enum TicketCategory {
    SAFETY("URGENT", Duration.ofHours(1), Duration.ofHours(24)),
    DAMAGE("HIGH", Duration.ofHours(12), Duration.ofHours(72)),
    NO_SHOW("HIGH", Duration.ofHours(12), Duration.ofHours(48)),
    QUALITY("NORMAL", Duration.ofHours(24), Duration.ofHours(72)),
    PAYMENT("NORMAL", Duration.ofHours(24), Duration.ofHours(72)),
    BEHAVIOUR("NORMAL", Duration.ofHours(24), Duration.ofHours(72)),
    PRIVACY("NORMAL", Duration.ofHours(72), Duration.ofDays(30)),
    OTHER("NORMAL", Duration.ofHours(24), Duration.ofHours(72));

    final String priority;
    final Duration firstResponse;
    final Duration resolution;

    TicketCategory(String priority, Duration firstResponse, Duration resolution) {
        this.priority = priority;
        this.firstResponse = firstResponse;
        this.resolution = resolution;
    }

    public String priority() {
        return priority;
    }
}
