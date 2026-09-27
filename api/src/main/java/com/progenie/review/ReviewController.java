package com.progenie.review;

import java.util.UUID;

import com.progenie.provider.GenieProfileDtos.ReplyRequest;
import com.progenie.review.ReviewService.ReviewDto;
import com.progenie.review.ReviewService.ReviewRequest;
import com.progenie.shared.security.CurrentUser;
import com.progenie.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Reviews: customers write them, everyone reads them, Genies reply. */
@RestController
public class ReviewController {

    private final ReviewService reviews;

    public ReviewController(ReviewService reviews) {
        this.reviews = reviews;
    }

    @PostMapping("/api/v1/bookings/{bookingId}/review")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewDto create(@PathVariable UUID bookingId, @Valid @RequestBody ReviewRequest req) {
        return reviews.create(CurrentUser.id(), bookingId, req);
    }

    /** Public, paginated. */
    @GetMapping("/api/v1/genies/{genieId}/reviews")
    public PageResponse<ReviewDto> forGenie(@PathVariable UUID genieId, @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "10") int size) {
        return reviews.forGenie(genieId, page, size);
    }

    @GetMapping("/api/v1/genie/reviews")
    public PageResponse<ReviewDto> mine(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return reviews.forGenie(CurrentUser.id(), page, size);
    }

    @PostMapping("/api/v1/genie/reviews/{reviewId}/reply")
    public ReviewDto reply(@PathVariable UUID reviewId, @Valid @RequestBody ReplyRequest req) {
        return reviews.reply(CurrentUser.id(), reviewId, req.reply());
    }
}
