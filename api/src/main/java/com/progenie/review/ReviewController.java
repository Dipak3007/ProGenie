package com.progenie.review;

import java.util.UUID;

import com.progenie.provider.GenieProfileDtos.ReplyRequest;
import com.progenie.review.ReviewService.ReviewDto;
import com.progenie.review.ReviewService.ReviewRequest;
import com.progenie.shared.security.CurrentUser;
import com.progenie.shared.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Reviews: customers write them, everyone reads them, Genies reply. */
@RestController
public class ReviewController {

    private final ReviewService reviews;
    private final CustomerRatings customerRatings;
    private final ReviewPhotos photos;

    public ReviewController(ReviewService reviews, CustomerRatings customerRatings, ReviewPhotos photos) {
        this.reviews = reviews;
        this.customerRatings = customerRatings;
        this.photos = photos;
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
        return reviews.forGenie(CurrentUser.id(), page, size, true);
    }

    @PostMapping("/api/v1/genie/reviews/{reviewId}/reply")
    public ReviewDto reply(@PathVariable UUID reviewId, @Valid @RequestBody ReplyRequest req) {
        return reviews.reply(CurrentUser.id(), reviewId, req.reply());
    }

    /** The Genie rates the customer right after completing the job (Phase 3.5A). Never shown to the customer. */
    @PostMapping("/api/v1/genie/bookings/{bookingId}/customer-rating")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerRatings.CustomerRatingDto rateCustomer(@PathVariable UUID bookingId,
                                                          @Valid @RequestBody CustomerRatings.CustomerRatingRequest req) {
        return customerRatings.rate(CurrentUser.id(), bookingId, req);
    }

    /** A photo of the finished work for the customer's review (multipart field "file"); up to 3, within 14 days. */
    @PostMapping("/api/v1/bookings/{bookingId}/review/photos")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewPhotos.ReviewPhotoDto addPhoto(@PathVariable UUID bookingId, @RequestPart("file") MultipartFile file) {
        return photos.add(CurrentUser.id(), bookingId, file);
    }

    @DeleteMapping("/api/v1/bookings/{bookingId}/review/photos/{photoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePhoto(@PathVariable UUID bookingId, @PathVariable UUID photoId) {
        photos.delete(CurrentUser.id(), bookingId, photoId);
    }

    /** The Genie reports a photo in a review of their work; an admin decides. */
    @PostMapping("/api/v1/genie/reviews/{reviewId}/photos/{photoId}/report")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reportPhoto(@PathVariable UUID reviewId, @PathVariable UUID photoId,
                            @Valid @RequestBody(required = false) ReviewPhotos.ReportRequest req) {
        photos.report(CurrentUser.id(), reviewId, photoId, req == null ? null : req.reason());
    }

    /** Public: redirects to a short-lived link to the photo's file (stable URL for lists and cached profiles). */
    @GetMapping("/api/v1/review-photos/{photoId}")
    public ResponseEntity<Void> photo(@PathVariable UUID photoId) {
        return ResponseEntity.status(HttpStatus.FOUND)
            .location(java.net.URI.create(photos.publicLink(photoId).url()))
            .cacheControl(CacheControl.maxAge(java.time.Duration.ofMinutes(1)).cachePrivate())
            .build();
    }
}
