package com.progenie.booking;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.progenie.provider.GenieDirectory.Interval;
import com.progenie.provider.GenieDirectory.Window;

/**
 * Pure slot maths (unit tested). A slot is bookable when it lies completely inside one of the
 * Genie's weekly availability windows (in the business time zone), starts on the slot step counted
 * from the window start, starts after {@code earliest}, and does not overlap a busy interval
 * (another active booking or time off).
 */
public final class SlotCalculator {

    private SlotCalculator() {
    }

    public record Slot(Instant start, Instant end) {
    }

    public static List<Slot> daySlots(LocalDate date, ZoneId zone, List<Window> windows, List<Interval> busy,
                                      Instant earliest, int stepMinutes, int durationMinutes) {
        List<Slot> slots = new ArrayList<>();
        int day = date.getDayOfWeek().getValue();
        List<Window> today = windows.stream()
            .filter(w -> w.dayOfWeek() == day)
            .sorted(Comparator.comparing(Window::startTime))
            .toList();
        for (Window w : today) {
            LocalTime t = w.startTime();
            while (true) {
                LocalTime end = t.plusMinutes(durationMinutes);
                // stop when the job would run past the window (or past midnight)
                if (end.isAfter(w.endTime()) || end.isBefore(t)) {
                    break;
                }
                Instant start = ZonedDateTime.of(date, t, zone).toInstant();
                Instant finish = start.plus(Duration.ofMinutes(durationMinutes));
                if (!start.isBefore(earliest) && !overlaps(start, finish, busy)) {
                    slots.add(new Slot(start, finish));
                }
                LocalTime next = t.plusMinutes(stepMinutes);
                if (!next.isAfter(t)) {
                    break; // wrapped past midnight
                }
                t = next;
            }
        }
        return slots;
    }

    /** True if a job starting at {@code start} fits inside an availability window and is aligned to the step. */
    public static boolean fitsAvailability(Instant start, int durationMinutes, ZoneId zone, List<Window> windows,
                                           int stepMinutes) {
        LocalDateTime localStart = LocalDateTime.ofInstant(start, zone);
        LocalDateTime localEnd = localStart.plusMinutes(durationMinutes);
        if (!localEnd.toLocalDate().equals(localStart.toLocalDate()) && !localEnd.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            return false;
        }
        int day = localStart.getDayOfWeek().getValue();
        LocalTime s = localStart.toLocalTime();
        LocalTime e = localEnd.toLocalTime();
        return windows.stream().anyMatch(w -> w.dayOfWeek() == day
            && !s.isBefore(w.startTime())
            && !e.isAfter(w.endTime())
            && !e.isBefore(s)
            && Duration.between(w.startTime(), s).toMinutes() % stepMinutes == 0
            && localStart.getSecond() == 0 && localStart.getNano() == 0);
    }

    public static boolean overlaps(Instant start, Instant end, List<Interval> busy) {
        return busy.stream().anyMatch(b -> start.isBefore(b.end()) && b.start().isBefore(end));
    }
}
