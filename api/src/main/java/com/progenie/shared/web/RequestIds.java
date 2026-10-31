package com.progenie.shared.web;

import java.util.concurrent.ThreadLocalRandom;

import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;

/**
 * Request and error IDs (Phase 3A-0). Every HTTP request gets an ID: it is in every log line written while the
 * request runs (MDC key {@value #MDC_REQUEST}), in the {@value #HEADER} response header, and in every error reply as
 * {@code errorId}, so "error ID K7Q2M9XF3T1A" from a tester leads straight to the matching lines in the logs.
 * Background jobs get one per run, prefixed {@code job-}.
 */
public final class RequestIds {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_REQUEST = "requestId";
    public static final String MDC_USER = "userId";

    /** Crockford base32 without look-alikes (no I, L, O, U): easy to read out over the phone. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int LENGTH = 12;

    private RequestIds() {
    }

    /** A new random ID, e.g. {@code K7Q2M9XF3T1A} (60 bits). */
    public static String next() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        char[] id = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            id[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(id);
    }

    /** The ID of the request (or job run) on this thread, if any. */
    public static @Nullable String current() {
        return MDC.get(MDC_REQUEST);
    }

    /** Runs {@code task} with its own {@code job-…} ID in the logs (scheduled jobs). */
    public static Runnable forJob(Runnable task) {
        return () -> {
            String previous = MDC.get(MDC_REQUEST);
            MDC.put(MDC_REQUEST, "job-" + next());
            try {
                task.run();
            } finally {
                if (previous == null) {
                    MDC.remove(MDC_REQUEST);
                } else {
                    MDC.put(MDC_REQUEST, previous);
                }
            }
        };
    }
}
