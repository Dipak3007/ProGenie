package com.progenie.admin;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.progenie.booking.BookingDtos.BookingDetailDto;
import com.progenie.booking.BookingDtos.BookingSummaryDto;
import com.progenie.booking.BookingQueryService;
import com.progenie.booking.BookingQueryService.Viewer;
import com.progenie.booking.BookingService;
import com.progenie.identity.domain.UserStatus;
import com.progenie.identity.service.AccountService;
import com.progenie.identity.service.AccountService.UserRowDto;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.security.CurrentUser;
import com.progenie.shared.web.PageResponse;
import com.progenie.support.SupportInboxService;
import com.progenie.support.SupportInboxService.ContactMessageDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Day-to-day operations: bookings, users and the support inbox (role ADMIN). */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminOperationsController {

    public record AdminCancelRequest(@NotBlank @Size(max = 300) String reason, boolean waiveFee) {
    }

    public record UserStatusRequest(@NotNull UserStatus status) {
    }

    public record MessageStatusRequest(@NotBlank String status) {
    }

    private final BookingQueryService bookingQueries;
    private final BookingService bookings;
    private final AccountService accounts;
    private final SupportInboxService inbox;

    public AdminOperationsController(BookingQueryService bookingQueries, BookingService bookings,
                                     AccountService accounts, SupportInboxService inbox) {
        this.bookingQueries = bookingQueries;
        this.bookings = bookings;
        this.accounts = accounts;
        this.inbox = inbox;
    }

    // ------------------------------------------------------------------ bookings

    @GetMapping("/bookings")
    public PageResponse<BookingSummaryDto> bookings(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return bookingQueries.adminSearch(status, q, from, to, page, size);
    }

    @GetMapping("/bookings/{id}")
    public BookingDetailDto booking(@PathVariable UUID id) {
        return bookingQueries.detail(id, Viewer.ADMIN, CurrentUser.id());
    }

    @PostMapping("/bookings/{id}/cancel")
    public BookingDetailDto cancel(@PathVariable UUID id, @Valid @RequestBody AdminCancelRequest req) {
        return bookings.adminCancel(CurrentUser.id(), id, req.reason(), req.waiveFee());
    }

    @PostMapping("/bookings/{id}/waive-fee")
    public BookingDetailDto waiveFee(@PathVariable UUID id) {
        return bookings.waiveFee(CurrentUser.id(), id);
    }

    // ------------------------------------------------------------------ users

    @GetMapping("/users")
    public PageResponse<UserRowDto> users(@RequestParam(required = false) String role,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String q,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return accounts.search(role, status, q, page, size);
    }

    /** ACTIVE or SUSPENDED. Suspending signs the user out everywhere. */
    @PutMapping("/users/{id}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void userStatus(@PathVariable UUID id, @Valid @RequestBody UserStatusRequest req) {
        if (id.equals(CurrentUser.id())) {
            throw ApiException.unprocessable("CANNOT_CHANGE_SELF", "You cannot change your own account status");
        }
        accounts.changeStatus(id, req.status());
    }

    // ------------------------------------------------------------------ support inbox

    @GetMapping("/contact-messages")
    public PageResponse<ContactMessageDto> messages(@RequestParam(required = false) String status,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return inbox.list(status, page, size);
    }

    /** NEW | IN_PROGRESS | RESOLVED */
    @PatchMapping("/contact-messages/{id}")
    public ContactMessageDto messageStatus(@PathVariable long id, @Valid @RequestBody MessageStatusRequest req) {
        return inbox.setStatus(id, req.status());
    }
}
