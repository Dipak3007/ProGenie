package com.progenie.booking;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.progenie.booking.BookingDtos.BookingDetailDto;
import com.progenie.booking.BookingDtos.BookingSummaryDto;
import com.progenie.booking.BookingDtos.CancelRequest;
import com.progenie.booking.BookingDtos.CreateBookingRequest;
import com.progenie.booking.BookingDtos.RescheduleRequest;
import com.progenie.booking.BookingDtos.StartCodeDto;
import com.progenie.booking.BookingDtos.TipRequest;
import com.progenie.booking.BookingQueryService.Scope;
import com.progenie.booking.BookingQueryService.Viewer;
import com.progenie.booking.SlotService.DayCountDto;
import com.progenie.booking.SlotService.DaySlotsDto;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.security.CurrentUser;
import com.progenie.shared.util.ServiceLines;
import com.progenie.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Customer bookings (/api/v1/bookings, role CUSTOMER) and public slot lookup (/api/v1/genies/{id}/slots). */
@RestController
public class BookingController {

    private final BookingService bookings;
    private final BookingQueryService queries;
    private final SlotService slots;
    private final BookingTracking tracking;
    private final Quotes quotes;

    public BookingController(BookingService bookings, BookingQueryService queries, SlotService slots,
                             BookingTracking tracking, Quotes quotes) {
        this.quotes = quotes;
        this.tracking = tracking;
        this.bookings = bookings;
        this.queries = queries;
        this.slots = slots;
    }

    /**
     * Free slots for one day. Example: GET /api/v1/genies/{id}/slots?serviceId=3&date=2026-10-01, or for several
     * services (Phase 3.5C) {@code items=3:2,7:1} (service id : quantity) instead of {@code serviceId}.
     */
    @GetMapping("/api/v1/genies/{genieId}/slots")
    public DaySlotsDto slots(@PathVariable UUID genieId, @RequestParam(required = false) @Nullable Long serviceId,
                             @RequestParam(required = false) @Nullable String items,
                             @RequestParam(required = false) @Nullable UUID quoteId,
                             @RequestParam(required = false) @Nullable @Min(15) @Max(480) Integer minutes,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (minutes != null) {
            // a slot of a given length: moving a booking keeps its length (quoted jobs, inspections)
            return slots.daySlotsFor(genieId, minutes, date);
        }
        if (quoteId != null) {
            // Phase 3.5D: times for a quoted job, as long as the Genie's estimate
            return slots.daySlotsFor(genieId, quotes.durationMinutes(quoteId, genieId), date);
        }
        return slots.daySlots(genieId, ServiceLines.parse(serviceId, items), date);
    }

    /** Free-slot counts for the next days, to grey out full days in the date picker. */
    @GetMapping("/api/v1/genies/{genieId}/slot-days")
    public List<DayCountDto> slotDays(@PathVariable UUID genieId, @RequestParam(required = false) @Nullable Long serviceId,
                                      @RequestParam(required = false) @Nullable String items,
                                      @RequestParam(required = false) @Nullable UUID quoteId,
                                      @RequestParam(required = false) @Nullable @Min(15) @Max(480) Integer minutes,
                                      @RequestParam(defaultValue = "7") int days) {
        if (minutes != null) {
            return slots.upcomingDaysFor(genieId, minutes, days);
        }
        if (quoteId != null) {
            return slots.upcomingDaysFor(genieId, quotes.durationMinutes(quoteId, genieId), days);
        }
        return slots.upcomingDays(genieId, ServiceLines.parse(serviceId, items), days);
    }

    /** Send an Idempotency-Key header (any unique string per attempt) so a retried request never double-books. */
    @PostMapping("/api/v1/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingDetailDto create(@Valid @RequestBody CreateBookingRequest req,
                                   @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        return bookings.create(CurrentUser.id(), req, idempotencyKey);
    }

    /** scope = ACTIVE (default) or PAST or ALL. */
    @GetMapping("/api/v1/bookings")
    public PageResponse<BookingSummaryDto> list(@RequestParam(defaultValue = "ACTIVE") Scope scope,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        if (scope == Scope.REQUESTS || scope == Scope.UPCOMING) {
            throw ApiException.badRequest("INVALID_SCOPE", "Use ACTIVE, PAST or ALL");
        }
        return queries.customerBookings(CurrentUser.id(), scope, page, size);
    }

    @GetMapping("/api/v1/bookings/{id}")
    public BookingDetailDto get(@PathVariable UUID id) {
        return queries.detail(id, Viewer.CUSTOMER, CurrentUser.id());
    }

    /** Live map while the Genie is on the way (Phase 3C); updates also arrive as {@code tracking} stream events. */
    @GetMapping("/api/v1/bookings/{id}/tracking")
    public BookingTracking.TrackingDto tracking(@PathVariable UUID id) {
        return tracking.get(id, Viewer.CUSTOMER, CurrentUser.id());
    }

    @PostMapping("/api/v1/bookings/{id}/cancel")
    public BookingDetailDto cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) CancelRequest req) {
        return bookings.cancel(CurrentUser.id(), id, req == null ? null : req.reason());
    }

    /** Phase 3.5C: drop one of several services until the Genie sets off (price, coupon and slot length follow). */
    @DeleteMapping("/api/v1/bookings/{id}/items/{itemId}")
    public BookingDetailDto removeService(@PathVariable UUID id, @PathVariable long itemId) {
        return bookings.removeService(CurrentUser.id(), id, itemId);
    }

    @PostMapping("/api/v1/bookings/{id}/reschedule")
    public BookingDetailDto reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleRequest req) {
        return bookings.reschedule(CurrentUser.id(), id, req.slotStart().toInstant());
    }

    @PutMapping("/api/v1/bookings/{id}/tip")
    public BookingDetailDto tip(@PathVariable UUID id, @Valid @RequestBody TipRequest req) {
        return bookings.changeTip(CurrentUser.id(), id, req.tipAmount());
    }

    @PostMapping("/api/v1/bookings/{id}/start-code")
    public StartCodeDto startCode(@PathVariable UUID id) {
        return bookings.startCode(CurrentUser.id(), id);
    }
}
