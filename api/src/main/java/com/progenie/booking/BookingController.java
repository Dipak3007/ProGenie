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
import com.progenie.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

    public BookingController(BookingService bookings, BookingQueryService queries, SlotService slots) {
        this.bookings = bookings;
        this.queries = queries;
        this.slots = slots;
    }

    /** Free slots for one day. Example: GET /api/v1/genies/{id}/slots?serviceId=3&date=2026-10-01 */
    @GetMapping("/api/v1/genies/{genieId}/slots")
    public DaySlotsDto slots(@PathVariable UUID genieId, @RequestParam long serviceId,
                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return slots.daySlots(genieId, serviceId, date);
    }

    /** Free-slot counts for the next days, to grey out full days in the date picker. */
    @GetMapping("/api/v1/genies/{genieId}/slot-days")
    public List<DayCountDto> slotDays(@PathVariable UUID genieId, @RequestParam long serviceId,
                                      @RequestParam(defaultValue = "7") int days) {
        return slots.upcomingDays(genieId, serviceId, days);
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

    @PostMapping("/api/v1/bookings/{id}/cancel")
    public BookingDetailDto cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) CancelRequest req) {
        return bookings.cancel(CurrentUser.id(), id, req == null ? null : req.reason());
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
