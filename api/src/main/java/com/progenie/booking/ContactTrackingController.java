package com.progenie.booking;

import com.progenie.booking.BookingTracking.ContactTrackingDto;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Public tracking link for the person at the address when a customer booked for someone else (Phase 3.5A). */
@RestController
public class ContactTrackingController {

    private final BookingTracking tracking;

    public ContactTrackingController(BookingTracking tracking) {
        this.tracking = tracking;
    }

    /** No login: the unguessable token is the key. Only answers while the Genie is on the way or working. */
    @GetMapping("/api/v1/track/{token}")
    public ContactTrackingDto track(@PathVariable String token) {
        return tracking.forContact(token);
    }
}
