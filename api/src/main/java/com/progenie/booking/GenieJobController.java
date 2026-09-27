package com.progenie.booking;

import java.util.UUID;

import com.progenie.booking.BookingDtos.BookingDetailDto;
import com.progenie.booking.BookingDtos.BookingSummaryDto;
import com.progenie.booking.BookingDtos.CancelRequest;
import com.progenie.booking.BookingDtos.CompleteRequest;
import com.progenie.booking.BookingDtos.DeclineRequest;
import com.progenie.booking.BookingDtos.StartRequest;
import com.progenie.booking.BookingQueryService.Scope;
import com.progenie.booking.BookingQueryService.Viewer;
import com.progenie.shared.security.CurrentUser;
import com.progenie.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A Genie's jobs (/api/v1/genie/bookings, role GENIE). */
@RestController
@RequestMapping("/api/v1/genie/bookings")
public class GenieJobController {

    private final GenieJobService jobs;
    private final BookingQueryService queries;

    public GenieJobController(GenieJobService jobs, BookingQueryService queries) {
        this.jobs = jobs;
        this.queries = queries;
    }

    /** scope = REQUESTS (new, waiting for you), UPCOMING (accepted/in progress), PAST or ALL. */
    @GetMapping
    public PageResponse<BookingSummaryDto> list(@RequestParam(defaultValue = "UPCOMING") Scope scope,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return queries.genieBookings(CurrentUser.id(), scope == Scope.ACTIVE ? Scope.UPCOMING : scope, page, size);
    }

    @GetMapping("/{id}")
    public BookingDetailDto get(@PathVariable UUID id) {
        return queries.detail(id, Viewer.GENIE, CurrentUser.id());
    }

    @PostMapping("/{id}/accept")
    public BookingDetailDto accept(@PathVariable UUID id) {
        return jobs.accept(CurrentUser.id(), id);
    }

    @PostMapping("/{id}/decline")
    public BookingDetailDto decline(@PathVariable UUID id, @Valid @RequestBody DeclineRequest req) {
        return jobs.decline(CurrentUser.id(), id, req.reason());
    }

    @PostMapping("/{id}/cancel")
    public BookingDetailDto cancel(@PathVariable UUID id, @Valid @RequestBody(required = false) CancelRequest req) {
        return jobs.cancel(CurrentUser.id(), id, req == null ? null : req.reason());
    }

    /** Body: {"code": "1234"} — the start code shown in the customer's app. */
    @PostMapping("/{id}/start")
    public BookingDetailDto start(@PathVariable UUID id, @Valid @RequestBody StartRequest req) {
        return jobs.start(CurrentUser.id(), id, req.code());
    }

    @PostMapping("/{id}/complete")
    public BookingDetailDto complete(@PathVariable UUID id, @Valid @RequestBody CompleteRequest req) {
        return jobs.complete(CurrentUser.id(), id, req);
    }
}
