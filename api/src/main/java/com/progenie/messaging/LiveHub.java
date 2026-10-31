package com.progenie.messaging;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;

import com.progenie.shared.error.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Open live-update streams (Server-Sent Events), by user. Business modules never call this directly: they
 * publish their usual events, and the notification module (LiveListeners) or {@link NotificationService} push a
 * small "something changed" message here after the transaction commits. The browser then reloads what it shows
 * (it never trusts the push alone).
 *
 * <p>In memory, so it works for one API instance (the free SIT runs one). Several instances would need a shared
 * channel such as PostgreSQL LISTEN/NOTIFY or Redis pub/sub in front of {@link #toUser}.
 */
@Component
public class LiveHub {

    private static final Logger log = LoggerFactory.getLogger(LiveHub.class);

    /** Streams end after this; the browser reconnects with a fresh token (access tokens live 15 minutes). */
    static final Duration STREAM_TTL = Duration.ofMinutes(10);
    static final int MAX_PER_USER = 5;
    static final int MAX_TOTAL = 1000;

    private record Client(UUID userId, String role, SseEmitter emitter) {
    }

    private final Map<UUID, Set<Client>> byUser = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();

    /** Opens a stream for the signed-in user. The first message ("ready") confirms it works. */
    public SseEmitter open(UUID userId, String role) {
        if (total.get() >= MAX_TOTAL) {
            throw ApiException.tooManyRequests("STREAM_LIMIT", "Too many live connections right now; the app falls back to refreshing");
        }
        SseEmitter emitter = new SseEmitter(STREAM_TTL.toMillis());
        Client client = new Client(userId, role, emitter);
        Set<Client> mine = byUser.computeIfAbsent(userId, k -> new CopyOnWriteArraySet<>());
        mine.add(client);
        total.incrementAndGet();
        emitter.onCompletion(() -> remove(client));
        emitter.onTimeout(() -> remove(client));
        emitter.onError(e -> remove(client));
        // A user with many tabs: keep the newest few
        if (mine.size() > MAX_PER_USER) {
            mine.stream().filter(c -> c != client).findFirst().ifPresent(oldest -> {
                remove(oldest);
                oldest.emitter().complete();
            });
        }
        log.debug("Live stream opened for user {} ({} open in total)", userId, total.get());
        send(client, SseEmitter.event().name("ready").reconnectTime(3000).data(Map.of("role", role == null ? "" : role),
            MediaType.APPLICATION_JSON));
        return emitter;
    }

    /** Pushes an event to every open stream of a user (no-op when they have none). */
    public void toUser(UUID userId, String event, Object data) {
        if (userId == null) {
            return;
        }
        Set<Client> clients = byUser.get(userId);
        if (clients != null) {
            clients.forEach(c -> send(c, SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON)));
        }
    }

    /** Pushes an event to everyone signed in with a role, e.g. all admins. */
    public void toRole(String role, String event, Object data) {
        byUser.values().forEach(set -> set.stream().filter(c -> role.equals(c.role()))
            .forEach(c -> send(c, SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON))));
    }

    /** Runs the push once the current transaction commits (never for a rollback), or now if there is none. */
    public void afterCommit(Runnable push) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    push.run();
                }
            });
        } else {
            push.run();
        }
    }

    /** Keeps idle streams open through proxies (Cloudflare drops a silent connection after 100 s). */
    @Scheduled(fixedRateString = "${progenie.live.heartbeat:20s}", initialDelayString = "20s")
    void heartbeat() {
        byUser.values().forEach(set -> set.forEach(c -> send(c, SseEmitter.event().comment("ping"))));
    }

    /**
     * On shutdown (a redeploy), end every stream first: otherwise graceful shutdown waits for them until it times
     * out. Browsers reconnect to the new instance on their own.
     */
    @EventListener(ContextClosedEvent.class)
    void closeAll() {
        byUser.values().forEach(set -> set.forEach(c -> {
            remove(c);
            try {
                c.emitter().complete();
            } catch (RuntimeException ignored) {
                // already closed
            }
        }));
    }

    public int openStreams() {
        return total.get();
    }

    List<UUID> connectedUsers() {
        return List.copyOf(byUser.keySet());
    }

    /** Users of a role with at least one open stream on this instance (Genie presence for Genie Now, Phase 3C-9). */
    public List<UUID> connectedUsers(String role) {
        return byUser.entrySet().stream()
            .filter(e -> e.getValue().stream().anyMatch(c -> role.equals(c.role())))
            .map(Map.Entry::getKey)
            .toList();
    }

    /** True if the user has an open stream on this instance. */
    public boolean isConnected(UUID userId) {
        Set<Client> clients = byUser.get(userId);
        return clients != null && !clients.isEmpty();
    }

    private void send(Client client, SseEmitter.SseEventBuilder event) {
        try {
            client.emitter().send(event);
        } catch (IOException | IllegalStateException e) {
            // the browser went away; Tomcat tells us on the next write
            remove(client);
            client.emitter().completeWithError(e);
        }
    }

    private void remove(Client client) {
        Set<Client> set = byUser.get(client.userId());
        if (set != null && set.remove(client)) {
            total.decrementAndGet();
            if (set.isEmpty()) {
                byUser.remove(client.userId(), set);
            }
            log.trace("Live stream closed for {}", client.userId());
        }
    }
}
