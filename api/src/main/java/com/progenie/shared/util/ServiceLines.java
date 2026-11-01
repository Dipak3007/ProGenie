package com.progenie.shared.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.progenie.shared.error.ApiException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * The services in one booking (Phase 3.5C): up to {@value #MAX_SERVICES} different services from the same Genie,
 * each 1–{@value #MAX_QUANTITY} times, taking at most {@value #MAX_MINUTES} minutes together. In a JSON body they are a
 * list of {@link Line}; in a query string {@code items=12:2,15:1} (service id : quantity).
 */
public final class ServiceLines {

    public static final int MAX_SERVICES = 5;
    public static final int MAX_QUANTITY = 5;
    /** One working day: longer jobs are split into several bookings. */
    public static final int MAX_MINUTES = 8 * 60;

    /** One service and how many of it (e.g. 2 fans). */
    public record ServiceLine(@NotNull Long serviceId, @Min(1) @Max(MAX_QUANTITY) int quantity) {
    }

    private ServiceLines() {
    }

    /**
     * The lines of a request: {@code items} when given, else the single {@code serviceId} (quantity 1). Checks the
     * limits: 400 ITEMS_REQUIRED, TOO_MANY_SERVICES, INVALID_QUANTITY, DUPLICATE_SERVICE.
     */
    public static List<ServiceLine> of(@Nullable Long serviceId, @Nullable List<ServiceLine> items) {
        List<ServiceLine> lines = items != null && !items.isEmpty() ? items : serviceId == null ? List.of() : List.of(new ServiceLine(serviceId, 1));
        return check(lines);
    }

    /** {@code items=12:2,15:1} from a query string, or the single {@code serviceId}. 400 INVALID_ITEMS when malformed. */
    public static List<ServiceLine> parse(@Nullable Long serviceId, @Nullable String items) {
        if (!StringUtils.hasText(items)) {
            return of(serviceId, null);
        }
        List<ServiceLine> lines = new ArrayList<>();
        for (String part : items.split(",")) {
            String[] pair = part.trim().split(":");
            try {
                long id = Long.parseLong(pair[0].trim());
                int quantity = pair.length > 1 ? Integer.parseInt(pair[1].trim()) : 1;
                if (pair.length > 2) {
                    throw new NumberFormatException(part);
                }
                lines.add(new ServiceLine(id, quantity));
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("INVALID_ITEMS", "items must look like 12:2,15:1 (service id : quantity)");
            }
        }
        return check(lines);
    }

    /** The query-string form of {@code lines}. */
    public static String format(List<ServiceLine> lines) {
        return String.join(",", lines.stream().map(l -> l.serviceId() + ":" + l.quantity()).toList());
    }

    private static List<ServiceLine> check(List<ServiceLine> lines) {
        if (lines.isEmpty()) {
            throw ApiException.badRequest("ITEMS_REQUIRED", "Choose at least one service");
        }
        if (lines.size() > MAX_SERVICES) {
            throw ApiException.badRequest("TOO_MANY_SERVICES", "A booking can have up to " + MAX_SERVICES + " different services");
        }
        Set<Long> seen = new HashSet<>();
        for (ServiceLine l : lines) {
            if (l.serviceId() == null || l.quantity() < 1 || l.quantity() > MAX_QUANTITY) {
                throw ApiException.badRequest("INVALID_QUANTITY", "Each service can be booked 1 to " + MAX_QUANTITY + " times");
            }
            if (!seen.add(l.serviceId())) {
                throw ApiException.badRequest("DUPLICATE_SERVICE", "Each service can be in a booking once (change its quantity instead)");
            }
        }
        return List.copyOf(lines);
    }
}
