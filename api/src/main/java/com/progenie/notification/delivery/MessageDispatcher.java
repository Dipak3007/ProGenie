package com.progenie.notification.delivery;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.progenie.notification.delivery.MessageOutbox.QueuedMessage;
import com.progenie.notification.delivery.MessageSender.DeliveryException;
import com.progenie.shared.util.Masking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends queued messages every few seconds. Rows are claimed with a lease (see {@link MessageOutbox#claimDue}),
 * sent outside any transaction, and then marked SENT, rescheduled (1, 5, 30, 120 minutes) or FAILED.
 * Safe to run on every replica at once.
 */
@Component
class MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);
    private static final int MAX_ROUNDS = 5;

    private final MessageOutbox outbox;
    private final MessagingProperties props;
    private final Map<String, MessageSender> senders;

    MessageDispatcher(MessageOutbox outbox, MessagingProperties props, List<MessageSender> senders) {
        this.outbox = outbox;
        this.props = props;
        this.senders = senders.stream().collect(Collectors.toMap(MessageSender::provider, Function.identity()));
    }

    @Scheduled(fixedDelayString = "${progenie.messaging.dispatch-interval:5s}", initialDelayString = "10s")
    public void dispatch() {
        if (!props.enabled()) {
            return;
        }
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<QueuedMessage> batch = outbox.claimDue(props.batchSize(), props.sendLease());
            batch.forEach(this::deliver);
            if (batch.size() < props.batchSize()) {
                return;
            }
        }
    }

    void deliver(QueuedMessage m) {
        String provider = props.providerFor(m.channel());
        MessageSender sender = senders.get(provider);
        if (sender == null || !sender.channels().contains(m.channel())) {
            outbox.markFailed(m.id(), provider, "No sender '" + provider + "' for " + m.channel());
            return;
        }
        try {
            String providerId = sender.send(m);
            outbox.markSent(m.id(), provider, providerId);
            log.debug("Sent {} {} to {}", m.channel(), m.template(), Masking.destination(m.destination()));
        } catch (RuntimeException ex) {
            DeliveryException de = ex instanceof DeliveryException d ? d : new DeliveryException(ex.toString(), true, ex);
            Duration delay = retryDelay(m.attempts());
            if (de.retryable() && delay != null) {
                outbox.reschedule(m.id(), provider, delay, de.getMessage());
                log.warn("{} {} to {} failed (attempt {}), retrying in {}: {}", m.channel(), m.template(),
                    Masking.destination(m.destination()), m.attempts(), delay, de.getMessage());
            } else {
                outbox.markFailed(m.id(), provider, de.getMessage());
                log.error("{} {} to {} FAILED after {} attempt(s): {}", m.channel(), m.template(),
                    Masking.destination(m.destination()), m.attempts(), de.getMessage());
            }
        }
    }

    /** Delay before the next attempt after {@code attemptsSoFar} tries, or null when no retries are left. */
    Duration retryDelay(int attemptsSoFar) {
        List<Duration> delays = props.retryDelays();
        return attemptsSoFar >= 1 && attemptsSoFar <= delays.size() ? delays.get(attemptsSoFar - 1) : null;
    }
}
