package com.progenie.booking;

import java.util.List;
import java.util.UUID;

import com.progenie.booking.BookingDtos.BookingDetailDto;
import com.progenie.booking.InstantBookings.InstantOfferDto;
import com.progenie.booking.InstantBookings.InstantQuoteDto;
import com.progenie.booking.InstantBookings.InstantRequestBody;
import com.progenie.booking.InstantBookings.InstantRequestDto;
import com.progenie.shared.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Genie Now (Phase 3C-9). Customers: /api/v1/instant/... (role CUSTOMER). Genies: /api/v1/genie/instant-offers/...
 * (role GENIE). Offers and request updates are also pushed over the live stream ({@code instant_offer} to the Genie,
 * {@code instant} to the customer); these endpoints are the fallback and the actions.
 */
@RestController
public class InstantController {

    private final InstantBookings instant;

    public InstantController(InstantBookings instant) {
        this.instant = instant;
    }

    /** Can the service be booked right away at this address, and the price range of the nearest free Genies. */
    @GetMapping("/api/v1/instant/quote")
    public InstantQuoteDto quote(@RequestParam long serviceId, @RequestParam UUID addressId) {
        return instant.quote(CurrentUser.id(), serviceId, addressId);
    }

    /** Starts the search: 201 with status SEARCHING, or NO_GENIE right away when nobody is free. */
    @PostMapping("/api/v1/instant/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public InstantRequestDto request(@Valid @RequestBody InstantRequestBody body) {
        return instant.request(CurrentUser.id(), body);
    }

    @GetMapping("/api/v1/instant/requests/{id}")
    public InstantRequestDto get(@PathVariable UUID id) {
        return instant.get(CurrentUser.id(), id);
    }

    /** Free while searching. */
    @PostMapping("/api/v1/instant/requests/{id}/cancel")
    public InstantRequestDto cancel(@PathVariable UUID id) {
        return instant.cancel(CurrentUser.id(), id);
    }

    /** The Genie's open offers (normally there is at most one). */
    @GetMapping("/api/v1/genie/instant-offers")
    public List<InstantOfferDto> offers() {
        return instant.openOffers(CurrentUser.id());
    }

    /** Takes the job: the booking, already accepted. 409 OFFER_GONE when it expired or the customer cancelled. */
    @PostMapping("/api/v1/genie/instant-offers/{id}/accept")
    public BookingDetailDto accept(@PathVariable long id) {
        return instant.accept(CurrentUser.id(), id);
    }

    @PostMapping("/api/v1/genie/instant-offers/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@PathVariable long id) {
        instant.decline(CurrentUser.id(), id);
    }
}
