package com.progenie.review;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.progenie.booking.BookingQueryService;
import com.progenie.booking.BookingQueryService.ReviewableBooking;
import com.progenie.provider.GenieProfileService;
import com.progenie.shared.error.ApiException;
import com.progenie.shared.web.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Verified reviews: only the customer of a completed booking can review it, once, within 14 days.
 * The Genie's average rating is recomputed from all reviews (never incremented, so it cannot drift).
 */
@Service
public class ReviewService {

    public record ReviewRequest(@Min(1) @Max(5) int rating, @Size(max = 1000) String comment) {
    }

    public record ReviewDto(UUID id, UUID bookingId, String bookingRef, String serviceName, int rating, String comment,
                            String customerName, OffsetDateTime createdAt, String genieReply, OffsetDateTime repliedAt) {
    }

    /** Published after a new review; the notification module tells the Genie. */
    public record ReviewSubmitted(UUID reviewId, UUID genieId, int rating) {
    }

    private static final String SELECT = """
        SELECT r.id, r.booking_id, b.booking_ref, s.name AS service_name, r.rating, r.comment,
               split_part(u.full_name, ' ', 1) AS customer_name, r.created_at, r.genie_reply, r.replied_at
          FROM reviews r
          JOIN bookings b ON b.id = r.booking_id
          JOIN services s ON s.id = b.service_id
          JOIN users u    ON u.id = r.customer_id
        """;

    private final JdbcClient jdbc;
    private final BookingQueryService bookings;
    private final GenieProfileService genies;
    private final ApplicationEventPublisher events;

    public ReviewService(JdbcClient jdbc, BookingQueryService bookings, GenieProfileService genies,
                         ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.bookings = bookings;
        this.genies = genies;
        this.events = events;
    }

    @Transactional
    public ReviewDto create(UUID customerId, UUID bookingId, ReviewRequest req) {
        ReviewableBooking booking = bookings.reviewable(customerId, bookingId);
        UUID id;
        try {
            id = jdbc.sql("""
                    INSERT INTO reviews (booking_id, customer_id, genie_id, rating, comment)
                    VALUES (:b, :c, :g, :rating, :comment) RETURNING id
                    """)
                .param("b", bookingId).param("c", customerId).param("g", booking.genieId())
                .param("rating", req.rating())
                .param("comment", StringUtils.hasText(req.comment()) ? req.comment().trim() : null)
                .query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("ALREADY_REVIEWED", "You have already reviewed this booking");
        }
        genies.refreshRating(booking.genieId());
        events.publishEvent(new ReviewSubmitted(id, booking.genieId(), req.rating()));
        return get(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<ReviewDto> forGenie(UUID genieId, int page, int size) {
        long total = jdbc.sql("SELECT count(*) FROM reviews WHERE genie_id = :g").param("g", genieId)
            .query(Long.class).single();
        List<ReviewDto> items = jdbc.sql(SELECT + " WHERE r.genie_id = :g ORDER BY r.created_at DESC LIMIT :limit OFFSET :offset")
            .param("g", genieId)
            .param("limit", PageResponse.clampSize(size)).param("offset", PageResponse.offset(page, size))
            .query(ReviewDto.class).list();
        return new PageResponse<>(items, Math.max(page, 0), PageResponse.clampSize(size), total);
    }

    /** A Genie can reply publicly to a review once. */
    @Transactional
    public ReviewDto reply(UUID genieId, UUID reviewId, String reply) {
        int rows = jdbc.sql("""
                UPDATE reviews SET genie_reply = :reply, replied_at = now()
                 WHERE id = :id AND genie_id = :g AND genie_reply IS NULL
                """)
            .param("reply", reply.trim()).param("id", reviewId).param("g", genieId).update();
        if (rows == 0) {
            boolean exists = jdbc.sql("SELECT EXISTS (SELECT 1 FROM reviews WHERE id = :id AND genie_id = :g)")
                .param("id", reviewId).param("g", genieId).query(Boolean.class).single();
            throw exists ? ApiException.conflict("ALREADY_REPLIED", "You have already replied to this review")
                : ApiException.notFound("REVIEW_NOT_FOUND", "Review not found");
        }
        return get(reviewId);
    }

    private ReviewDto get(UUID id) {
        return jdbc.sql(SELECT + " WHERE r.id = :id").param("id", id).query(ReviewDto.class).single();
    }
}
