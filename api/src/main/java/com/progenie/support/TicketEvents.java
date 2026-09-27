package com.progenie.support;

import java.util.UUID;

/** Published by the support module; the notification module turns them into in-app and email messages. */
public final class TicketEvents {

    private TicketEvents() {
    }

    /**
     * @param customerId the booking's customer (null for privacy requests)
     * @param genieId    the booking's Genie (null for privacy requests)
     */
    public record TicketRaised(UUID ticketId, String ticketRef, UUID bookingId, String bookingRef, UUID raisedBy,
                               String raisedByRole, UUID customerId, UUID genieId, String category, String priority,
                               String subject) {
    }

    /** A visible (non-internal) message was added. {@code authorRole}: CUSTOMER, GENIE or ADMIN. */
    public record TicketReplied(UUID ticketId, String ticketRef, UUID bookingId, String bookingRef, UUID authorId,
                                String authorRole, UUID raisedBy, UUID customerId, UUID genieId, boolean awaitingReply,
                                String excerpt) {
    }

    /** {@code outcome}: RESOLVED or REJECTED; {@code note} is shown to the customer. */
    public record TicketResolved(UUID ticketId, String ticketRef, UUID bookingId, String bookingRef, UUID raisedBy,
                                 UUID customerId, UUID genieId, String outcome, String actions, String note) {
    }
}
