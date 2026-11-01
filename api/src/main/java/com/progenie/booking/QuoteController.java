package com.progenie.booking;

import java.util.UUID;

import com.progenie.booking.BookingDtos.AcceptQuoteRequest;
import com.progenie.booking.BookingDtos.BookingDetailDto;
import com.progenie.booking.BookingDtos.CancelRequest;
import com.progenie.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The customer's answer to a Genie's quote after an inspection visit (Phase 3.5D). */
@RestController
public class QuoteController {

    private final Quotes quotes;

    public QuoteController(Quotes quotes) {
        this.quotes = quotes;
    }

    /** Books the quoted job at the chosen time (already accepted by the Genie); returns the new booking. */
    @PostMapping("/api/v1/quotes/{id}/accept")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingDetailDto accept(@PathVariable UUID id, @Valid @RequestBody AcceptQuoteRequest req) {
        return quotes.accept(CurrentUser.id(), id, req);
    }

    /** Returns the inspection booking with the declined quote. */
    @PostMapping("/api/v1/quotes/{id}/decline")
    public BookingDetailDto decline(@PathVariable UUID id, @Valid @RequestBody(required = false) CancelRequest req) {
        return quotes.decline(CurrentUser.id(), id, req == null ? null : req.reason());
    }
}
