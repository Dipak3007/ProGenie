package com.progenie.booking;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.progenie.booking.SlotCalculator.Slot;
import com.progenie.provider.GenieDirectory.Interval;
import com.progenie.provider.GenieDirectory.Window;
import com.progenie.provider.GenieDirectory;
import com.progenie.pricing.PricingService;
import com.progenie.shared.config.AppProperties;
import com.progenie.shared.config.QueryConstants;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.util.QueryUtil;
import com.progenie.shared.util.ServiceLines.ServiceLine;
import com.progenie.shared.util.Times;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Free time slots for a Genie and one or more services (Phase 3.5C: the slot is as long as all of them together), and
 * the server-side check used when booking.
 */
@Service
public class SlotService {

    /** {@code serviceId}: the first service; {@code durationMinutes}: how long all of them take. */
    public record DaySlotsDto(LocalDate date, UUID genieId, long serviceId, int durationMinutes, List<Slot> slots) {
    }

    public record DayCountDto(LocalDate date, int available) {
    }

    private final JdbcClient jdbc;
    private final GenieDirectory directory;
    private final PricingService pricing;
    private final AppProperties.Booking rules;
    private final ZoneId zone;
    private final Clock clock;

    public SlotService(JdbcClient jdbc, GenieDirectory directory, PricingService pricing, AppProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.directory = directory;
        this.pricing = pricing;
        this.rules = props.booking();
        this.zone = ZoneId.of(props.timezone());
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DaySlotsDto daySlots(UUID genieId, List<ServiceLine> lines, LocalDate date) {
        int duration = duration(genieId, lines);
        return new DaySlotsDto(date, genieId, lines.getFirst().serviceId(), duration, compute(genieId, duration, date, null));
    }

    /** Free slots of a given length (Phase 3.5D: a quoted job takes the Genie's estimate). */
    @Transactional(readOnly = true)
    public DaySlotsDto daySlotsFor(UUID genieId, int minutes, LocalDate date) {
        return new DaySlotsDto(date, genieId, 0, minutes, compute(genieId, minutes, date, null));
    }

    @Transactional(readOnly = true)
    public List<DayCountDto> upcomingDaysFor(UUID genieId, int minutes, int days) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        int count = Math.clamp(days, 1, rules.horizonDays());
        List<DayCountDto> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDate date = today.plusDays(i);
            result.add(new DayCountDto(date, compute(genieId, minutes, date, null).size()));
        }
        return result;
    }

    /** How long the lines take with this Genie (who must be bookable for the first one). */
    private int duration(UUID genieId, List<ServiceLine> lines) {
        directory.bookableOffer(genieId, lines.getFirst().serviceId());
        return pricing.durationMinutes(genieId, lines);
    }

    /** Number of free slots per day for the next {@code days} days (for a date picker). */
    @Transactional(readOnly = true)
    public List<DayCountDto> upcomingDays(UUID genieId, List<ServiceLine> lines, int days) {
        int duration = duration(genieId, lines);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        int count = Math.clamp(days, 1, rules.horizonDays());
        List<DayCountDto> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDate date = today.plusDays(i);
            result.add(new DayCountDto(date, compute(genieId, duration, date, null).size()));
        }
        return result;
    }

    /** Throws SLOT_UNAVAILABLE unless the slot is currently free and within the booking rules. */
    @Transactional(readOnly = true)
    public void assertBookable(UUID genieId, Instant start, int durationMinutes, UUID excludeBookingId) {
        Instant now = clock.instant();
        Instant end = start.plus(Duration.ofMinutes(durationMinutes));
        if (start.isBefore(now.plus(rules.minLeadTime()))) {
            throw ApiException.unprocessable("SLOT_TOO_SOON",
                "Please pick a slot at least " + rules.minLeadTime().toHours() + " hours from now");
        }
        if (start.isAfter(now.plus(Duration.ofDays(rules.horizonDays())))) {
            throw ApiException.unprocessable("SLOT_TOO_FAR", "You can book up to " + rules.horizonDays() + " days ahead");
        }
        List<Window> windows = directory.weeklyAvailability(genieId);
        if (!SlotCalculator.fitsAvailability(start, durationMinutes, zone, windows, rules.slotStepMinutes())) {
            throw ApiException.unprocessable("SLOT_UNAVAILABLE", "The Genie is not available at that time");
        }
        List<Interval> busy = new ArrayList<>(directory.timeOff(genieId, start, end));
        busy.addAll(activeBookings(genieId, start, end, excludeBookingId));
        if (SlotCalculator.overlaps(start, end, busy)) {
            throw ApiException.conflict("SLOT_TAKEN", "That slot was just taken, please pick another one");
        }
    }

    private List<Slot> compute(UUID genieId, int duration, LocalDate date, UUID excludeBookingId) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (date.isBefore(today) || date.isAfter(today.plusDays(rules.horizonDays()))) {
            return List.of();
        }
        Instant dayStart = date.atStartOfDay(zone).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
        List<Interval> busy = new ArrayList<>(directory.timeOff(genieId, dayStart, dayEnd));
        busy.addAll(activeBookings(genieId, dayStart, dayEnd, excludeBookingId));
        Instant earliest = clock.instant().plus(rules.minLeadTime());
        return SlotCalculator.daySlots(date, zone, directory.weeklyAvailability(genieId), busy, earliest,
            rules.slotStepMinutes(), duration);
    }

    private List<Interval> activeBookings(UUID genieId, Instant from, Instant to, UUID excludeBookingId) {
        record Row(OffsetDateTime slotStart, OffsetDateTime slotEnd) {
        }
        return jdbc.sql(QueryUtil.get(QueryConstants.GET_BOOKING_ACTIVE_SLOTS))
            .param("g", genieId)
            .param("from", Times.odt(from))
            .param("to", Times.odt(to))
            .param("exclude", excludeBookingId)
            .query(Row.class)
            .list().stream()
            .map(r -> new Interval(r.slotStart().toInstant(), r.slotEnd().toInstant()))
            .toList();
    }
}
