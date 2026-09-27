package com.progenie.notification.delivery;

import java.util.Set;

import com.progenie.notification.delivery.MessageOutbox.QueuedMessage;

/**
 * Port for one delivery provider. Adapters: {@link MailpitSender} (local) and {@link Msg91Sender} (live).
 * The dispatcher picks the adapter per channel from {@code progenie.messaging.*-provider}.
 */
interface MessageSender {

    /** Name used in configuration and stored on the outbox row, e.g. "mailpit". */
    String provider();

    Set<Channel> channels();

    /** Sends one message and returns the provider's message id, or throws {@link DeliveryException}. */
    String send(QueuedMessage message);

    /** A failed send. {@code retryable} is false for errors that another attempt can't fix (bad number, no template). */
    final class DeliveryException extends RuntimeException {

        private final boolean retryable;

        DeliveryException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        DeliveryException(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
        }

        boolean retryable() {
            return retryable;
        }
    }
}
